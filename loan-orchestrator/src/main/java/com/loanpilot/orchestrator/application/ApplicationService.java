package com.loanpilot.orchestrator.application;

import static com.loanpilot.orchestrator.decision.DecisionEngine.rupees;
import static org.springframework.util.StringUtils.hasText;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.loanpilot.orchestrator.application.ApplicationResult.Income;
import com.loanpilot.orchestrator.application.ApplicationResult.LlmCall;
import com.loanpilot.orchestrator.application.ApplicationResult.MemoCheck;
import com.loanpilot.orchestrator.application.ApplicationResult.Slip;
import com.loanpilot.orchestrator.application.ApplicationResult.Step;
import com.loanpilot.orchestrator.audit.AuditLog;
import com.loanpilot.orchestrator.decision.ApplicantFacts;
import com.loanpilot.orchestrator.decision.Decision;
import com.loanpilot.orchestrator.decision.Decision.Outcome;
import com.loanpilot.orchestrator.decision.DecisionEngine;
import com.loanpilot.orchestrator.decision.RuleCheck;
import com.loanpilot.orchestrator.extract.ExtractRequest;
import com.loanpilot.orchestrator.extract.ExtractResponse;
import com.loanpilot.orchestrator.extract.ExtractService;
import com.loanpilot.orchestrator.guard.LlmGuardAdvisor;
import com.loanpilot.orchestrator.policy.Citations;
import com.loanpilot.orchestrator.policy.PolicyChunk;
import com.loanpilot.orchestrator.policy.PolicyLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * One loan application, from documents to credit memo. A fixed WORKFLOW, not an agent: every application needs
 * the same steps, so code runs them in order and the LLM never chooses what happens next.
 *
 *   1 BUREAU    credit report over MCP, called by code (POC 5 server)           cheap, so first: fail fast
 *   2 INCOME    LLM extracts each salary slip, validation checks it (POC 2)     AI reads
 *   3 DECIDE    DecisionEngine: pure Java rules                                 code decides (rule 1)
 *   4 POLICY    the text of every clause the rules used, looked up by §         lookup, not search
 *   5 MEMO      LLM explains the decision; code checks the memo matches it      AI explains
 *
 * If the facts can't be trusted (a slip fails validation, slips are for the same month, names differ), the engine
 * is NOT run on bad data: the outcome is REFER and code writes the memo. A bureau outage is a 503, not an outcome.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    /** "Recommendation: APPROVE", tolerating markdown like "**Recommendation:** APPROVE". */
    private static final Pattern RECOMMENDATION =
            Pattern.compile("(?im)^\\W*recommendation\\W*[:\\-]?\\W*(APPROVE|REFER|REJECT)\\b");

    private final BureauClient bureau;
    private final ExtractService extractService;
    private final DecisionEngine engine;
    private final PolicyLibrary policy;
    private final AuditLog auditLog;
    private final ChatClient chatClient;
    private final Resource memoPrompt;
    private final String memoPromptVersion;
    private final String defaultModel;
    private final double temperature;
    private final int seed;

    public ApplicationService(BureauClient bureau,
                              ExtractService extractService,
                              DecisionEngine engine,
                              PolicyLibrary policy,
                              AuditLog auditLog,
                              ChatClient.Builder builder,
                              @Value("${loanpilot.memo.prompt}") Resource memoPrompt,
                              @Value("${loanpilot.memo.prompt-version}") String memoPromptVersion,
                              @Value("${spring.ai.ollama.chat.model}") String defaultModel,
                              @Value("${loanpilot.memo.temperature}") double temperature,
                              @Value("${loanpilot.memo.seed}") int seed) {
        this.bureau = bureau;
        this.extractService = extractService;
        this.engine = engine;
        this.policy = policy;
        this.auditLog = auditLog;
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
        this.memoPrompt = memoPrompt;
        this.memoPromptVersion = memoPromptVersion;
        this.defaultModel = defaultModel;
        this.temperature = temperature;
        this.seed = seed;
    }

    public ApplicationResult process(LoanApplicationRequest request) {
        if (!request.fastMode() && request.salarySlips().size() != 3) {
            throw new IllegalArgumentException("Send the last 3 salary slips (§2.1), got " + request.salarySlips().size());
        }
        if (request.fastMode() && request.declaredNetMonthlyIncome() == null) {
            throw new IllegalArgumentException("Send 3 salarySlips, or declaredNetMonthlyIncome for fast mode");
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        // Every LLM call below (slips, memo) is audited under this id by the guard (POC 8).
        MDC.put(LlmGuardAdvisor.MDC_REQUEST_ID, id);
        try {
            return run(request, id);
        } finally {
            MDC.remove(LlmGuardAdvisor.MDC_REQUEST_ID);
        }
    }

    private ApplicationResult run(LoanApplicationRequest request, String id) {
        long start = System.currentTimeMillis();
        List<Step> steps = new ArrayList<>();
        log.info("[app {}] Application for {}: {} over {} months, {} slip(s){}", id, request.applicantId(),
                rupees(request.amount()), request.tenureMonths(), request.fastMode() ? 0 : 3,
                request.fastMode() ? " (FAST MODE: declared income)" : "");

        // 1 BUREAU
        long t = System.currentTimeMillis();
        BureauReport report = bureau.creditReport(request.applicantId(), id);
        steps.add(new Step("BUREAU", "OK", System.currentTimeMillis() - t,
                "status=" + report.status() + ", score=" + report.cibilScore() + " (MCP getCreditReport)"));
        log.info("[app {}] 1/5 Bureau: status={}, score={}", id, report.status(), report.cibilScore());

        // 2 INCOME
        t = System.currentTimeMillis();
        Income income;
        String factProblem = null;
        if (request.fastMode()) {
            income = new Income("DECLARED", request.declaredNetMonthlyIncome(), List.of());
            steps.add(new Step("INCOME", "OK", 0, "declared income, NOT verified (fast mode)"));
        } else {
            List<Slip> slips = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < 3; i++) {
                log.info("[app {}] 2/5 Extracting salary slip {}/3...", id, i + 1);
                ExtractResponse r = extractService.extract(new ExtractRequest(request.salarySlips().get(i), null));
                slips.add(new Slip(r.slip() != null ? r.slip().payPeriod() : null,
                        r.slip() != null ? r.slip().netPay() : null, r.valid(), r.errors(), r.promptVersion(),
                        r.inputSha256(), r.latencyMs()));
                if (r.slip() != null && r.slip().employeeName() != null) {
                    names.add(r.slip().employeeName().strip().toLowerCase(Locale.ROOT));
                }
            }
            factProblem = slipProblem(slips, names);
            BigDecimal average = factProblem != null ? null : slips.stream().map(Slip::netPay)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(3), 0, RoundingMode.HALF_UP);
            income = new Income("SALARY_SLIPS", average, List.copyOf(slips));
            steps.add(new Step("INCOME", factProblem == null ? "OK" : "FAILED", System.currentTimeMillis() - t,
                    factProblem == null ? "average net pay of 3 slips = " + rupees(average) : factProblem));
        }
        log.info("[app {}] 2/5 Income: {} ({}){}", id, income.netMonthlyIncome() != null ? rupees(income.netMonthlyIncome()) : "-",
                income.source(), factProblem != null ? " PROBLEM " + factProblem : "");

        if (factProblem != null) {
            // Never decide on facts we couldn't establish. Code refers it; no LLM writes this memo.
            steps.add(new Step("DECIDE", "SKIPPED", 0, "facts not established: " + factProblem));
            steps.add(new Step("POLICY", "SKIPPED", 0, ""));
            steps.add(new Step("MEMO", "OK", 0, "written by code, no LLM call"));
            String memo = "Recommendation: REFER\n\nSummary: Application for applicant " + request.applicantId()
                    + " could not be assessed automatically (" + factProblem + "). The decision engine was not run. "
                    + "A credit officer must review the salary slips.";
            return new ApplicationResult(id, request.applicantId(), Outcome.REFER, List.of(factProblem), null, null,
                    income, report, memo,
                    new MemoCheck("NOT_GENERATED", "REFER", List.of(), List.of(), null, null, null, null, null, List.of()),
                    steps, llmCalls(id), System.currentTimeMillis() - start);
        }

        // 3 DECIDE
        t = System.currentTimeMillis();
        ApplicantFacts facts = new ApplicantFacts(request.applicantId(), request.employmentType(), request.amount(),
                request.tenureMonths(), income.netMonthlyIncome(), report.status(), report.cibilScore(),
                Objects.requireNonNullElse(report.totalExistingEmi(), BigDecimal.ZERO),
                Objects.requireNonNullElse(report.dpd90Count(), 0), Objects.requireNonNullElse(report.enquiries6m(), 0));
        Decision decision = engine.decide(facts);
        steps.add(new Step("DECIDE", "OK", System.currentTimeMillis() - t,
                decision.outcome() + " " + decision.reasonCodes() + ", FOIR " + decision.foirPercent() + "%, "
                        + decision.rulesVersion()));
        log.info("[app {}] 3/5 Decision: {} {} (FOIR {}%, EMI {}, rate {}%)", id, decision.outcome(),
                decision.reasonCodes(), decision.foirPercent(), rupees(decision.emi()), decision.interestRate());

        // 4 POLICY: clause TEXT only for what needs explaining (FAIL / REFER) plus §1.3, looked up by section.
        // Passed checks already carry the engine's own reason. Sending all 13 clauses (credit-memo-v1) cost ~1,800
        // prompt tokens and invited paraphrase: the model read §4.8 (DPD + bounces) and wrote "bounces: PASS".
        t = System.currentTimeMillis();
        Set<String> sections = new LinkedHashSet<>();   // every section the memo may cite
        decision.checks().forEach(c -> sections.add(c.clause()));
        sections.add("1.3");   // decision authority: the officer decides
        Set<String> explain = new LinkedHashSet<>();
        decision.checks().stream()
                .filter(c -> c.status() == RuleCheck.Status.FAIL || c.status() == RuleCheck.Status.REFER)
                .forEach(c -> explain.add(c.clause()));
        explain.add("1.3");
        List<PolicyChunk> clauses = explain.stream().map(policy::clause).flatMap(java.util.Optional::stream).toList();
        steps.add(new Step("POLICY", "OK", System.currentTimeMillis() - t,
                clauses.size() + " clause text(s) by section: " + clauses.stream().map(PolicyChunk::section).toList()));

        // 5 MEMO
        t = System.currentTimeMillis();
        log.info("[app {}] 5/5 Writing memo: model={}, prompt={}. Waiting...", id, defaultModel, memoPromptVersion);
        String input = memoInput(request, income, report, decision, clauses);
        ChatResponse response = chatClient.prompt()
                .options(OllamaChatOptions.builder().temperature(temperature).seed(seed))
                .system(memoPrompt)
                .user(input)
                .advisors(a -> a.param(LlmGuardAdvisor.PURPOSE, "credit-memo")
                        .param(LlmGuardAdvisor.PROMPT_VERSION, memoPromptVersion))
                .call()
                .chatResponse();
        String memo = response.getResult().getOutput().getText();
        memo = hasText(memo) ? memo : "";
        MemoCheck check = checkMemo(memo, input, decision, income.netMonthlyIncome(), sections, response,
                memoPromptVersion, defaultModel);
        steps.add(new Step("MEMO", "OK".equals(check.status()) ? "OK" : "FAILED", System.currentTimeMillis() - t,
                "check=" + check.status() + ", faithful=" + check.faithful()
                        + (check.faithfulnessIssues().isEmpty() ? "" : " " + check.faithfulnessIssues())));
        if (!"OK".equals(check.status())) {
            log.warn("[app {}] 5/5 Memo check {}: memo says {}, engine says {}, unsupported citations {}", id,
                    check.status(), check.memoRecommendation(), decision.outcome(), check.unsupportedCitations());
        }
        long totalMs = System.currentTimeMillis() - start;
        log.info("[app {}] Done in {} ms: {} (memo check {})", id, totalMs, decision.outcome(), check.status());

        return new ApplicationResult(id, request.applicantId(), decision.outcome(), decision.reasonCodes(), decision,
                facts, income, report, memo, check, steps, llmCalls(id), totalMs);
    }

    /** This application's LLM calls, as the guard recorded them. Counts and hashes only; outputs stay in the log. */
    private List<LlmCall> llmCalls(String id) {
        return auditLog.find(id).stream()
                .map(r -> new LlmCall(r.purpose(), r.model(), r.promptVersion(), r.inputSha256(), r.piiMasked(),
                        r.promptTokens(), r.completionTokens(), r.latencyMs(), r.id()))
                .toList();
    }

    /** Facts we can't trust are a reason to stop, not to guess. Returns a reason code, or null when all is well. */
    static String slipProblem(List<Slip> slips, Set<String> names) {
        if (slips.stream().anyMatch(s -> !s.valid())) {
            return "SLIP_EXTRACTION_FAILED";
        }
        if (slips.stream().map(Slip::payPeriod).distinct().count() < slips.size()) {
            return "DUPLICATE_PAY_PERIOD";
        }
        if (names.size() > 1) {
            return "NAME_MISMATCH_ACROSS_SLIPS";
        }
        return null;
    }

    /** Everything the memo may use, built by code. No name or PAN: the memo refers to the applicant id. */
    static String memoInput(LoanApplicationRequest request, Income income, BureauReport report, Decision d,
                            List<PolicyChunk> clauses) {
        StringBuilder sb = new StringBuilder();
        sb.append("Application: applicant ").append(request.applicantId()).append(", ").append(request.employmentType())
                .append(", requested ").append(rupees(request.amount())).append(" over ").append(request.tenureMonths())
                .append(" months").append(hasText(request.purpose()) ? ", purpose: " + request.purpose() : "").append("\n");
        sb.append("Recommendation: ").append(d.outcome()).append("\n");
        sb.append("Reason codes: ").append(d.reasonCodes().isEmpty() ? "none" : String.join(", ", d.reasonCodes())).append("\n");
        sb.append("Summary opening: ").append(summaryOpening(d)).append("\n");
        sb.append("Income source: ").append("DECLARED".equals(income.source())
                ? "declared by the applicant, NOT verified" : "average net pay of the last 3 salary slips").append("\n\n");

        sb.append("Figures:\n");
        sb.append("- Net monthly income: ").append(rupees(income.netMonthlyIncome())).append("\n");
        sb.append("- Existing EMIs: ").append(rupees(Objects.requireNonNullElse(report.totalExistingEmi(), BigDecimal.ZERO))).append("\n");
        sb.append("- New EMI: ").append(rupees(d.emi())).append(" at ").append(d.interestRate().stripTrailingZeros().toPlainString())
                .append("% per annum\n");
        sb.append("- FOIR: ").append(d.foirPercent()).append("%\n");
        sb.append("- CIBIL score: ").append(report.cibilScore() != null ? report.cibilScore() : "none (" + report.status() + ")")
                .append(", credit enquiries in 6 months: ").append(report.enquiries6m())
                .append(", accounts 90+ days past due: ").append(report.dpd90Count()).append("\n");
        sb.append("- Maximum eligible loan: ").append(rupees(d.maxLoanAmount())).append("\n\n");

        sb.append("Rule checks:\n");
        for (RuleCheck c : d.checks()) {
            sb.append("- ").append(c.status()).append(" §").append(c.clause());
            if (c.status() == RuleCheck.Status.FAIL || c.status() == RuleCheck.Status.REFER) {
                sb.append(" [").append(c.code()).append("]");
            }
            sb.append(": ").append(c.reason()).append(" (actual ").append(c.actual()).append("; limit ").append(c.limit()).append(")\n");
        }

        sb.append("\nPolicy sections:\n");
        for (PolicyChunk c : clauses) {
            sb.append("[§").append(c.section()).append("] ").append(c.text()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * The memo's conclusion sentence, chosen by code. A 7B model asked to pick between two sentences by a condition
     * blends them ("recommends APPROVE because all checks passed"), so the choice is made here and the model copies it.
     */
    static String summaryOpening(Decision d) {
        boolean problems = d.checks().stream()
                .anyMatch(c -> c.status() == RuleCheck.Status.FAIL || c.status() == RuleCheck.Status.REFER);
        if (problems) {
            return "The engine recommends " + d.outcome() + " because";
        }
        boolean unverified = d.checks().stream().anyMatch(c -> c.status() == RuleCheck.Status.NOT_CHECKED);
        return unverified
                ? "The checks the engine could run passed; the items under Not verified still need the credit officer."
                : "Every check the engine ran passed.";
    }

    /**
     * Decided by code. Consistency (status): the memo states the engine's recommendation and cites only sections it
     * was given. Faithfulness (POC 8): it states only figures from its input, includes the key ones, discloses what
     * wasn't verified and claims nothing more.
     */
    static MemoCheck checkMemo(String memo, String memoInput, Decision decision, BigDecimal netMonthlyIncome,
                               Set<String> providedSections, ChatResponse response, String promptVersion,
                               String defaultModel) {
        Outcome outcome = decision.outcome();
        Matcher m = RECOMMENDATION.matcher(memo);
        String said = m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
        List<String> cited = Citations.find(memo);
        List<String> unsupported = cited.stream().filter(c -> !providedSections.contains(c)).toList();
        String status = said == null ? "MISSING_RECOMMENDATION"
                : !said.equals(outcome.name()) ? "OUTCOME_MISMATCH"
                : !unsupported.isEmpty() ? "UNSUPPORTED_CITATION"
                : "OK";
        String model = response.getMetadata().getModel();
        Usage usage = response.getMetadata().getUsage();
        MemoFaithfulness.Result faithfulness = MemoFaithfulness.check(memo, memoInput, decision, netMonthlyIncome);
        return new MemoCheck(status, said, cited, unsupported, hasText(model) ? model : defaultModel, promptVersion,
                usage != null ? usage.getPromptTokens() : null, usage != null ? usage.getCompletionTokens() : null,
                faithfulness.faithful(), faithfulness.issues());
    }
}
