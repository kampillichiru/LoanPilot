package com.loanpilot.orchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.loanpilot.orchestrator.application.ApplicationResult.Income;
import com.loanpilot.orchestrator.decision.ApplicantFacts;
import com.loanpilot.orchestrator.decision.ApplicantFacts.EmploymentType;
import com.loanpilot.orchestrator.decision.Decision;
import com.loanpilot.orchestrator.decision.DecisionEngine;
import org.junit.jupiter.api.Test;

/**
 * The faithfulness check against real memos. The two v2 memos below were written by qwen2.5:7b in the POC 7 live
 * eval and passed POC 7's memo check (right outcome, valid citations). They are still not faithful.
 */
class MemoFaithfulnessTest {

    private final DecisionEngine engine = new DecisionEngine();

    private record Case(Decision decision, String input, BigDecimal income) {
    }

    private Case priya() {
        return build("APP-001", "500000", 36, "85000", 762, "20000", 0, 1, "Home renovation");
    }

    private Case sneha() {
        return build("APP-004", "800000", 48, "110000", 805, "12500", 0, 0, "Education");
    }

    private Case build(String id, String amount, int months, String income, int score, String emi, int dpd, int enq,
                       String purpose) {
        ApplicantFacts facts = new ApplicantFacts(id, EmploymentType.SALARIED, new BigDecimal(amount), months,
                new BigDecimal(income), "FOUND", score, new BigDecimal(emi), dpd, enq);
        Decision d = engine.decide(facts);
        LoanApplicationRequest request = new LoanApplicationRequest(id, EmploymentType.SALARIED, new BigDecimal(amount),
                months, purpose, List.of(), new BigDecimal(income));
        BureauReport report = new BureauReport(id, "FOUND", score, 2, new BigDecimal(emi), dpd, enq, "2026-10-01");
        String input = ApplicationService.memoInput(request, new Income("DECLARED", new BigDecimal(income), List.of()),
                report, d, List.of());
        return new Case(d, input, new BigDecimal(income));
    }

    private static MemoFaithfulness.Result check(String memo, Case c) {
        return MemoFaithfulness.check(memo, c.input(), c.decision(), c.income());
    }

    static final String PRIYA_V2 = """
            Recommendation: APPROVE

            Summary: APP-001 requested Rs 500,000 for 36 months for home renovation. The engine approved the \
            application based on meeting all policy criteria.

            Key figures: Net monthly income Rs 85,000, existing EMIs Rs 20,000, new EMI Rs 16,607, FOIR 43.1%, CIBIL \
            score 762, maximum eligible loan Rs 1,700,000.

            Failed or referred: None.

            Passed: §5.1, §5.2, §3.3, §4.5, §4.8, §6.1, §4.2, §4.7, §4.6.

            Not verified: §3.1, §3.4, §4.8, §7.3.
            """;

    static final String SNEHA_V2 = """
            Recommendation: APPROVE

            Summary: APP-004 requested Rs 800,000 for education. The engine approved based on meeting all policy criteria.

            Key figures: Net monthly income Rs 110,000, existing EMIs Rs 12,500, new EMI Rs 20,676, CIBIL score 805.

            Failed or referred: None.

            Passed: §5.1, §5.2, §3.3, §4.5, §6.2, §4.2, §4.7, §4.6.

            Not verified: §3.1, no data source for age at application and at loan end. §3.4, no data source for work \
            experience and time with employer. §4.8, no data source for bounces in the last 6 months. §7.3, no data \
            source for salary slips vs bank statement credits.
            """;

    static final String PRIYA_GOOD = """
            Recommendation: APPROVE

            Summary: APP-001 requested Rs 500,000 over 36 months for home renovation. The engine recommends approval: \
            FOIR, CIBIL score and loan amount are within policy.

            Key figures: Net monthly income Rs 85,000; Existing EMIs Rs 20,000; New EMI Rs 16,607; Interest rate 12% \
            per annum; FOIR 43.1%; CIBIL score 762; Maximum eligible loan Rs 1,700,000.

            Failed or referred: None.

            Passed: §5.1, §5.2, §3.3, §4.5, §4.8, §6.1, §4.2, §4.7, §4.6

            Not verified: §3.1 age; §3.4 employment stability; §4.8 bounces; §7.3 salary credits.
            """;

    @Test
    void priyasV2MemoOverclaimsAndLeavesOutTheRate() {
        MemoFaithfulness.Result r = check(PRIYA_V2, priya());

        assertThat(r.faithful()).isFalse();
        assertThat(r.issues()).containsExactly(
                "MISSING_FIGURE: interest rate 12%",
                "OVERCLAIM: \"all policy criteria\" while 4 section(s) were not verified");
    }

    @Test
    void snehasV2MemoLeavesOutHerPreferentialRateAndFoir() {
        MemoFaithfulness.Result r = check(SNEHA_V2, sneha());

        assertThat(r.faithful()).isFalse();
        assertThat(r.issues()).contains("MISSING_FIGURE: interest rate 11%", "MISSING_FIGURE: FOIR 30.2%")
                .anyMatch(i -> i.startsWith("OVERCLAIM"));
    }

    @Test
    void aMemoThatStatesTheFiguresAndDisclosesTheGapsIsFaithful() {
        MemoFaithfulness.Result r = check(PRIYA_GOOD, priya());

        assertThat(r.issues()).isEmpty();
        assertThat(r.faithful()).isTrue();
    }

    @Test
    void anInventedNumberIsCaught() {
        String memo = PRIYA_GOOD.replace("FOIR, CIBIL score", "Her savings of Rs 3,40,000, FOIR, CIBIL score");

        assertThat(check(memo, priya()).issues()).containsExactly("UNSUPPORTED_NUMBER: 340000");
    }

    @Test
    void lakhsAreTheSameFigureNotAnInventedOne() {
        String memo = PRIYA_GOOD.replace("requested Rs 500,000", "requested Rs 5 lakh");

        assertThat(check(memo, priya()).faithful()).isTrue();
    }

    @Test
    void anUnverifiedSectionOnThePassedLineIsCaught() {
        String memo = PRIYA_GOOD.replace("§4.7, §4.6\n", "§4.7, §4.6, §7.3\n");

        assertThat(check(memo, priya()).issues()).containsExactly("UNVERIFIED_CLAIMED_PASSED: §7.3");
    }

    @Test
    void notMentioningAnUnverifiedSectionIsCaught() {
        String memo = PRIYA_GOOD.replace("; §7.3 salary credits", "");

        assertThat(check(memo, priya()).issues()).containsExactly("UNVERIFIED_NOT_DISCLOSED: §7.3");
    }

    @Test
    void aRejectionMustCiteEveryFailedSection() {
        // Karan: CIBIL 698 (FAIL §4.5) and 7 enquiries (REFER §4.6). This memo only mentions §4.5.
        Case karan = build("APP-005", "600000", 36, "95000", 698, "26000", 0, 7, "Travel");
        String memo = """
                Recommendation: REJECT

                Summary: APP-005 requested Rs 600,000. CIBIL score 698 is below the minimum of 700 (§4.5).

                Key figures: Net monthly income Rs 95,000; New EMI Rs 19,929; Interest rate 12%; FOIR 48.3%.

                Not verified: §3.1; §3.4; §4.8; §7.3.
                """;

        assertThat(check(memo, karan).issues()).containsExactly("REASON_NOT_CITED: §4.6");
    }
}
