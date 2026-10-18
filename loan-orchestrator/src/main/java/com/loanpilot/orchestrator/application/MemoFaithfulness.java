package com.loanpilot.orchestrator.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.loanpilot.orchestrator.decision.Decision;
import com.loanpilot.orchestrator.decision.DecisionEngine;
import com.loanpilot.orchestrator.decision.RuleCheck;
import com.loanpilot.orchestrator.decision.RuleCheck.Status;
import com.loanpilot.orchestrator.policy.Citations;

/**
 * Is the memo faithful to the decision? POC 7's memo check only compared the outcome and the citations. These
 * checks catch what slipped through it (see LEARNINGS, POC 7), all in plain code, no second LLM:
 *
 *   UNSUPPORTED_NUMBER        a number in the memo that isn't in the input the model was given (invented or computed)
 *   MISSING_FIGURE            a key figure left out: income, new EMI, interest rate, FOIR (Sneha's memo dropped 11%)
 *   OVERCLAIM                 "meets all criteria" while some checks were NOT_CHECKED
 *   UNVERIFIED_NOT_DISCLOSED  a NOT_CHECKED section the memo never mentions
 *   REASON_NOT_CITED          a FAIL / REFER section the memo never mentions
 *   UNVERIFIED_CLAIMED_PASSED a section on the "Passed:" line that was only NOT_CHECKED
 *   FAILED_CLAIMED_PASSED     a section on the "Passed:" line that failed
 *
 * Limits, on purpose: a number that appears anywhere in the input is accepted, even next to the wrong label; tone
 * and emphasis aren't judged. An LLM-as-judge could cover those, at the cost of another slow, non-deterministic call.
 */
final class MemoFaithfulness {

    record Result(boolean faithful, List<String> issues) {
    }

    private static final Pattern CITATION = Pattern.compile("(?:§|\\b[Ss]ection|\\b[Cc]lause)\\s*\\d+\\.\\d+");
    private static final Pattern APPLICANT_ID = Pattern.compile("\\bAPP-\\d+\\b");
    private static final Pattern NUMBER =
            Pattern.compile("(?i)(?<![\\w.])(\\d[\\d,]*(?:\\.\\d+)?)(\\s*(?:lakhs?|crores?)\\b)?");
    private static final Pattern PERCENT = Pattern.compile("(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern OVERCLAIM = Pattern.compile("(?i)\\b(?:all|every)\\s+(?:of\\s+the\\s+)?"
            + "(?:(?:policy|eligibility|credit|lending)\\s+)?(?:criteria|requirements|conditions|checks|rules|norms|parameters)\\b"
            + "|\\bfully\\s+(?:eligible|compliant)\\b|\\bno\\s+(?:concerns|issues|risks)\\b");
    private static final Pattern PASSED_LINE = Pattern.compile("(?im)^\\W*passed\\W*[:\\-]?(.*)$");

    private MemoFaithfulness() {
    }

    static Result check(String memo, String memoInput, Decision d, BigDecimal netMonthlyIncome) {
        List<String> issues = new ArrayList<>();
        Set<BigDecimal> stated = numbers(memo);

        // 1 Grounding: every number in the memo must come from the input.
        Set<BigDecimal> given = numbers(memoInput);
        stated.stream().filter(n -> !given.contains(n))
                .forEach(n -> issues.add("UNSUPPORTED_NUMBER: " + n.toPlainString()));

        // 2 Completeness: the figures an officer needs first.
        Set<BigDecimal> percents = percents(memo);
        if (!stated.contains(norm(netMonthlyIncome))) {
            issues.add("MISSING_FIGURE: net monthly income " + DecisionEngine.rupees(netMonthlyIncome));
        }
        if (!stated.contains(norm(d.emi()))) {
            issues.add("MISSING_FIGURE: new EMI " + DecisionEngine.rupees(d.emi()));
        }
        if (!percents.contains(norm(d.interestRate()))) {
            issues.add("MISSING_FIGURE: interest rate " + d.interestRate().stripTrailingZeros().toPlainString() + "%");
        }
        if (!percents.contains(norm(d.foirPercent()))) {
            issues.add("MISSING_FIGURE: FOIR " + d.foirPercent().toPlainString() + "%");
        }

        // 3 Overclaim: nothing "meets all criteria" while checks are unverified.
        Set<String> notChecked = clauses(d, Status.NOT_CHECKED);
        Matcher over = OVERCLAIM.matcher(memo);
        if (!notChecked.isEmpty() && over.find()) {
            issues.add("OVERCLAIM: \"" + over.group() + "\" while " + notChecked.size() + " section(s) were not verified");
        }

        // 4 Disclosure: every unverified and every failed / referred section is mentioned.
        Set<String> cited = new HashSet<>(Citations.find(memo));
        Set<String> failed = new LinkedHashSet<>(clauses(d, Status.FAIL));
        failed.addAll(clauses(d, Status.REFER));
        notChecked.stream().filter(c -> !cited.contains(c)).forEach(c -> issues.add("UNVERIFIED_NOT_DISCLOSED: §" + c));
        failed.stream().filter(c -> !cited.contains(c)).forEach(c -> issues.add("REASON_NOT_CITED: §" + c));

        // 5 The "Passed:" line lists only sections that passed. §4.8 holds two rules (DPD checked, bounces not), so a
        //   section counts as passed when at least one of its checks passed.
        Set<String> passed = clauses(d, Status.PASS);
        Matcher line = PASSED_LINE.matcher(memo);
        while (line.find()) {
            for (String c : Citations.find(line.group(1))) {
                if (passed.contains(c)) {
                    continue;
                }
                if (failed.contains(c)) {
                    issues.add("FAILED_CLAIMED_PASSED: §" + c);
                } else if (notChecked.contains(c)) {
                    issues.add("UNVERIFIED_CLAIMED_PASSED: §" + c);
                }
            }
        }
        return new Result(issues.isEmpty(), List.copyOf(issues));
    }

    /** Every number in the text, normalised: "5,00,000", "500,000", "5 lakh" are all 500000. Section numbers and
     *  applicant ids are not figures, so they're removed first. */
    static Set<BigDecimal> numbers(String text) {
        String cleaned = APPLICANT_ID.matcher(CITATION.matcher(text).replaceAll(" ")).replaceAll(" ");
        Set<BigDecimal> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(cleaned);
        while (m.find()) {
            String digits = m.group(1).replace(",", "");
            if (digits.isEmpty() || digits.endsWith(".")) {
                continue;
            }
            BigDecimal value = new BigDecimal(digits);
            String unit = m.group(2) == null ? "" : m.group(2).strip().toLowerCase();
            if (unit.startsWith("lakh")) {
                value = value.multiply(BigDecimal.valueOf(100_000));
            } else if (unit.startsWith("crore")) {
                value = value.multiply(BigDecimal.valueOf(10_000_000));
            }
            out.add(norm(value));
        }
        return out;
    }

    private static Set<BigDecimal> percents(String text) {
        Matcher m = PERCENT.matcher(text);
        Set<BigDecimal> out = new HashSet<>();
        while (m.find()) {
            out.add(norm(new BigDecimal(m.group(1))));
        }
        return out;
    }

    private static Set<String> clauses(Decision d, Status status) {
        return d.checks().stream().filter(c -> c.status() == status).map(RuleCheck::clause)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 12, 12.0 and 12.00 are the same figure. */
    private static BigDecimal norm(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }
}
