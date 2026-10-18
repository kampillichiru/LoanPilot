package com.loanpilot.orchestrator.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.loanpilot.orchestrator.decision.ApplicantFacts.EmploymentType;
import com.loanpilot.orchestrator.decision.Decision.Outcome;
import com.loanpilot.orchestrator.decision.RuleCheck.Status;
import org.junit.jupiter.api.Test;

/**
 * The decision engine is plain Java, so it's tested like plain Java: exact numbers, every boundary.
 * The five applicants match mcp-credit-bureau's synthetic data and data-generator/applicants.py.
 */
class DecisionEngineTest {

    private final DecisionEngine engine = new DecisionEngine();

    static ApplicantFacts facts(String id, String income, String bureauStatus, Integer score, String existingEmi,
                                int dpd90, int enquiries, String amount, int months) {
        return new ApplicantFacts(id, EmploymentType.SALARIED, new BigDecimal(amount), months, new BigDecimal(income),
                bureauStatus, score, new BigDecimal(existingEmi), dpd90, enquiries);
    }

    static ApplicantFacts priya() {
        return facts("APP-001", "85000", "FOUND", 762, "20000", 0, 1, "500000", 36);
    }

    private static RuleCheck check(Decision d, String code) {
        return d.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    // --- the five applicants ---------------------------------------------------------------------------

    @Test
    void priyaIsApprovedWithTheNumbersFromTheArchitectureDoc() {
        Decision d = engine.decide(priya());

        assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(d.reasonCodes()).isEmpty();
        assertThat(d.emi()).isEqualByComparingTo("16607");
        assertThat(d.foirPercent()).isEqualByComparingTo("43.1");
        assertThat(d.interestRate()).isEqualByComparingTo("12");      // 762 >= 750, but FOIR 43.1% > 40%
        assertThat(d.maxLoanAmount()).isEqualByComparingTo("1700000"); // 20 x 85,000
        assertThat(check(d, "FOIR_EXCEEDED").clause()).isEqualTo("4.2");
        assertThat(d.rulesVersion()).isEqualTo("rules-v1/credit-policy-v1");
    }

    @Test
    void snehaGetsThePreferentialRate() {
        Decision d = engine.decide(facts("APP-004", "110000", "FOUND", 805, "12500", 0, 0, "800000", 48));

        assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(d.interestRate()).isEqualByComparingTo("11");
        assertThat(d.emi()).isEqualByComparingTo(EmiCalculator.monthlyEmi(new BigDecimal("800000"), new BigDecimal("11"), 48));
        assertThat(check(d, "INTEREST_RATE").clause()).isEqualTo("6.2");
    }

    @Test
    void rahulIsRejectedForEveryReasonNotJustTheFirst() {
        Decision d = engine.decide(facts("APP-002", "70000", "FOUND", 641, "18000", 1, 5, "300000", 24));

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(d.reasonCodes()).containsExactly("CIBIL_BELOW_MINIMUM", "DPD90_IN_LAST_24M", "CREDIT_HUNGER");
    }

    @Test
    void karanIsRejectedOneScorePointBelowTheMinimum() {
        Decision d = engine.decide(facts("APP-005", "95000", "FOUND", 698, "26000", 0, 7, "600000", 36));

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);   // a FAIL beats a REFER
        assertThat(d.reasonCodes()).containsExactly("CIBIL_BELOW_MINIMUM", "CREDIT_HUNGER");
        assertThat(check(d, "FOIR_EXCEEDED").status()).isEqualTo(Status.PASS);
    }

    @Test
    void arjunWithNoCreditHistoryIsApprovedUnderTheNoHistoryRule() {
        Decision d = engine.decide(facts("APP-003", "62000", "NO_CREDIT_HISTORY", null, "0", 0, 0, "250000", 24));

        assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(check(d, "NO_CREDIT_HISTORY_NOT_ELIGIBLE").status()).isEqualTo(Status.PASS);
        assertThat(d.maxLoanAmount()).isEqualByComparingTo("300000");   // §4.4 cap, not 20 x income
        assertThat(d.checks()).noneMatch(c -> c.code().equals("CIBIL_BELOW_MINIMUM"));
    }

    // --- boundaries --------------------------------------------------------------------------------------

    @Test
    void foirOfExactly50PercentPassesAndOneRupeeMoreFails() {
        BigDecimal emi = EmiCalculator.monthlyEmi(new BigDecimal("100000"), new BigDecimal("12"), 12);
        String existingAtLimit = new BigDecimal("50000").subtract(emi).toPlainString();   // total = 50% of 100,000
        String existingOver = new BigDecimal("50001").subtract(emi).toPlainString();

        Decision atLimit = engine.decide(facts("X", "100000", "FOUND", 720, existingAtLimit, 0, 0, "100000", 12));
        Decision over = engine.decide(facts("X", "100000", "FOUND", 720, existingOver, 0, 0, "100000", 12));

        assertThat(atLimit.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(over.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(over.reasonCodes()).containsExactly("FOIR_EXCEEDED");
        // 50.001% displays as 50.0% but is still above the limit: compare exact, round only for display
        assertThat(over.foirPercent()).isEqualByComparingTo("50.0");
    }

    @Test
    void cibilOf700PassesAnd699Fails() {
        assertThat(engine.decide(facts("X", "85000", "FOUND", 700, "0", 0, 0, "500000", 36)).outcome())
                .isEqualTo(Outcome.APPROVE);
        assertThat(engine.decide(facts("X", "85000", "FOUND", 699, "0", 0, 0, "500000", 36)).reasonCodes())
                .containsExactly("CIBIL_BELOW_MINIMUM");
    }

    @Test
    void fourEnquiriesPassAndFiveReferToAnOfficer() {
        assertThat(engine.decide(facts("X", "85000", "FOUND", 760, "0", 0, 4, "500000", 36)).outcome())
                .isEqualTo(Outcome.APPROVE);
        Decision five = engine.decide(facts("X", "85000", "FOUND", 760, "0", 0, 5, "500000", 36));
        assertThat(five.outcome()).isEqualTo(Outcome.REFER);
        assertThat(five.reasonCodes()).containsExactly("CREDIT_HUNGER");
    }

    @Test
    void loanOfExactly20TimesIncomePassesAndOneRupeeMoreFails() {
        assertThat(engine.decide(facts("X", "50000", "FOUND", 760, "0", 0, 0, "1000000", 60)).outcome())
                .isEqualTo(Outcome.APPROVE);
        assertThat(engine.decide(facts("X", "50000", "FOUND", 760, "0", 0, 0, "1000001", 60)).reasonCodes())
                .contains("LOAN_ABOVE_MAXIMUM");
    }

    @Test
    void highEarnersAreStillCappedAt25Lakh() {
        Decision d = engine.decide(facts("X", "500000", "FOUND", 800, "0", 0, 0, "2500000", 60));

        assertThat(d.maxLoanAmount()).isEqualByComparingTo("2500000");   // not 20 x 5,00,000
        assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
    }

    @Test
    void preferentialRateNeedsFoirOf40OrLessAtTheStandardRate() {
        BigDecimal emi = EmiCalculator.monthlyEmi(new BigDecimal("100000"), new BigDecimal("12"), 12);
        String existingAt40 = new BigDecimal("40000").subtract(emi).toPlainString();
        String existingOver40 = new BigDecimal("40001").subtract(emi).toPlainString();

        assertThat(engine.decide(facts("X", "100000", "FOUND", 750, existingAt40, 0, 0, "100000", 12)).interestRate())
                .isEqualByComparingTo("11");
        assertThat(engine.decide(facts("X", "100000", "FOUND", 750, existingOver40, 0, 0, "100000", 12)).interestRate())
                .isEqualByComparingTo("12");
        assertThat(engine.decide(facts("X", "100000", "FOUND", 749, existingAt40, 0, 0, "100000", 12)).interestRate())
                .isEqualByComparingTo("12");
    }

    @Test
    void amountAndTenureOutsideTheProductRangeAreRejected() {
        assertThat(engine.decide(facts("X", "85000", "FOUND", 760, "0", 0, 0, "49999", 36)).reasonCodes())
                .containsExactly("AMOUNT_OUT_OF_RANGE");
        assertThat(engine.decide(facts("X", "85000", "FOUND", 760, "0", 0, 0, "500000", 61)).reasonCodes())
                .containsExactly("TENURE_OUT_OF_RANGE");
        // 1 lakh, not 5: 5 lakh over 11 months is an EMI of ~47,000, which would (correctly) fail FOIR too
        assertThat(engine.decide(facts("X", "85000", "FOUND", 760, "0", 0, 0, "100000", 11)).reasonCodes())
                .containsExactly("TENURE_OUT_OF_RANGE");
    }

    @Test
    void noHistoryAboveThreeLakhIsRejected() {
        Decision d = engine.decide(facts("X", "62000", "NO_CREDIT_HISTORY", null, "0", 0, 0, "300001", 24));

        assertThat(d.reasonCodes()).containsExactly("NO_CREDIT_HISTORY_NOT_ELIGIBLE", "LOAN_ABOVE_MAXIMUM");
    }

    @Test
    void selfEmployedUseTheStricterLimits() {
        ApplicantFacts f = new ApplicantFacts("X", EmploymentType.SELF_EMPLOYED, new BigDecimal("700000"), 36,
                new BigDecimal("45000"), "FOUND", 760, BigDecimal.ZERO, 0, 0);

        Decision d = engine.decide(f);

        assertThat(check(d, "FOIR_EXCEEDED").clause()).isEqualTo("4.3");
        assertThat(d.maxLoanAmount()).isEqualByComparingTo("675000");   // 15 x 45,000
        assertThat(d.reasonCodes()).contains("LOAN_ABOVE_MAXIMUM");
    }

    @Test
    void applicantUnknownToTheBureauIsReferredNotGuessed() {
        Decision d = engine.decide(facts("X", "85000", "NOT_FOUND", null, "0", 0, 0, "500000", 36));

        assertThat(d.outcome()).isEqualTo(Outcome.REFER);
        assertThat(d.reasonCodes()).containsExactly("BUREAU_NOT_FOUND");
    }

    // --- properties ---------------------------------------------------------------------------------------

    @Test
    void rulesWithoutDataAreListedAsNotCheckedNeverAsPassed() {
        assertThat(engine.decide(priya()).checks())
                .filteredOn(c -> c.status() == Status.NOT_CHECKED)
                .extracting(RuleCheck::clause)
                .containsExactly("3.1", "3.4", "4.8", "7.3");
    }

    @Test
    void sameFactsAlwaysGiveTheSameDecision() {
        assertThat(engine.decide(priya())).isEqualTo(engine.decide(priya()));
    }

    @Test
    void refusesToDecideWithoutIncome() {
        assertThatThrownBy(() -> engine.decide(facts("X", "0", "FOUND", 760, "0", 0, 0, "500000", 36)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
