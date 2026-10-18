package com.loanpilot.orchestrator.decision;

import java.math.BigDecimal;

/**
 * Every number the decision engine uses, each next to the policy clause it comes from
 * (policy-docs/credit-policy-v1.md). When the policy changes, this file and its tests change with it
 * (ADR-0001: rules are maintained in code, reviewed and tested like code).
 */
final class PolicyLimits {

    static final String POLICY_VERSION = "credit-policy-v1";

    // §5.1 Loan amount range
    static final BigDecimal MIN_AMOUNT = new BigDecimal("50000");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("2500000");

    // §5.2 Tenure
    static final int MIN_TENURE_MONTHS = 12;
    static final int MAX_TENURE_MONTHS = 60;

    // §3.3 Minimum net monthly income
    static final BigDecimal MIN_INCOME_SALARIED = new BigDecimal("25000");
    static final BigDecimal MIN_INCOME_SELF_EMPLOYED = new BigDecimal("40000");

    // §4.5 Minimum CIBIL score
    static final int MIN_CIBIL = 700;

    // §4.4 Applicants with no credit history
    static final BigDecimal THIN_FILE_MIN_INCOME = new BigDecimal("50000");
    static final BigDecimal THIN_FILE_MAX_AMOUNT = new BigDecimal("300000");

    // §4.2 / §4.3 Maximum FOIR (percent)
    static final BigDecimal MAX_FOIR_SALARIED = new BigDecimal("50");
    static final BigDecimal MAX_FOIR_SELF_EMPLOYED = new BigDecimal("45");

    // §4.7 Maximum loan amount: multiple of net monthly income, absolute cap
    static final BigDecimal LOAN_MULTIPLE_SALARIED = new BigDecimal("20");
    static final BigDecimal LOAN_MULTIPLE_SELF_EMPLOYED = new BigDecimal("15");
    static final BigDecimal LOAN_CAP = new BigDecimal("2500000");

    // §4.6 More than this many enquiries in 6 months -> refer to a credit officer
    static final int MAX_ENQUIRIES_6M = 4;

    // §4.8 Accounts 90+ days past due in the last 24 months
    static final int MAX_DPD90_ACCOUNTS = 0;

    // §6.1 / §6.2 Interest rates (percent per annum)
    static final BigDecimal STANDARD_RATE = new BigDecimal("12");
    static final BigDecimal PREFERENTIAL_RATE = new BigDecimal("11");
    static final int PREFERENTIAL_MIN_CIBIL = 750;
    static final BigDecimal PREFERENTIAL_MAX_FOIR = new BigDecimal("40");

    private PolicyLimits() {
    }
}
