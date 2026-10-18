package com.loanpilot.orchestrator.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The spec for PiiMasker. All values are synthetic (rule 5).
 * Written before the code: make these pass without changing the assertions.
 */
class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker();

    /** A real slip from data-generator/out/applicants/APP-001.json. */
    private static final String PRIYA_SLIP = """
            ACME INFOTECH PVT LTD
            Employee Name : Priya Sharma
            Employee ID   : EMP1001
            PAN           : ZZZPZ0001Z
            Bank A/c No.  : 0000 0000 0001

            PAYSLIP FOR THE MONTH OF JULY 2026

            EARNINGS                            DEDUCTIONS
            Basic                     37,100    Provident Fund             1,800
            HRA                       14,800    Professional Tax             200
            Conveyance                 1,600    Income Tax (TDS)           6,720
            Special Allowance         39,220

            Gross Earnings            92,720    Total Deductions           8,720
            Net Pay: 84,000
            """;

    // ---------- each type is masked ----------

    @Test
    void masksPan() {
        MaskResult r = masker.mask("PAN : ZZZPZ0001Z");
        assertThat(r.maskedText()).isEqualTo("PAN : [PAN]");
        assertThat(r.count(PiiType.PAN)).isEqualTo(1);
    }

    @Test
    void masksAadhaarWithSpacesHyphensOrNone() {
        assertThat(masker.mask("Aadhaar: 2345 6789 0123").maskedText()).isEqualTo("Aadhaar: [AADHAAR]");
        assertThat(masker.mask("Aadhaar: 2345-6789-0123").maskedText()).isEqualTo("Aadhaar: [AADHAAR]");
        assertThat(masker.mask("UID 234567890123").maskedText()).isEqualTo("UID [AADHAAR]");
    }

    @Test
    void labelledBankAccountIsAnAccountNumberEvenIfItLooksLikeAadhaar() {
        MaskResult r = masker.mask("Bank A/c No.  : 0000 0000 0001");
        assertThat(r.maskedText()).isEqualTo("Bank A/c No.  : [ACCOUNT_NUMBER]");
        assertThat(r.count(PiiType.ACCOUNT_NUMBER)).isEqualTo(1);
        assertThat(r.count(PiiType.AADHAAR)).isZero();
    }

    @Test
    void masksAccountNumbersOfDifferentLengthsWhenLabelled() {
        assertThat(masker.mask("Account No: 001234567890123").maskedText()).isEqualTo("Account No: [ACCOUNT_NUMBER]");
        assertThat(masker.mask("A/c 123456789").maskedText()).isEqualTo("A/c [ACCOUNT_NUMBER]");
    }

    @Test
    void masksIndianMobileNumbers() {
        assertThat(masker.mask("Mobile: 9876543210").maskedText()).isEqualTo("Mobile: [PHONE]");
        assertThat(masker.mask("Ph +91 98765 43210").maskedText()).isEqualTo("Ph [PHONE]");
        assertThat(masker.mask("call +91-9876543210 now").maskedText()).isEqualTo("call [PHONE] now");
    }

    @Test
    void masksEmail() {
        assertThat(masker.mask("mail priya.sharma@example.com today").maskedText())
                .isEqualTo("mail [EMAIL] today");
    }

    // ---------- what must NOT be masked: the LLM needs it ----------

    @Test
    void keepsMoneyMonthsAndEmployeeIds() {
        String text = "Basic 37,100  Gross 92,720  Net Pay: 84,000  Rs 1,25,000  JULY 2026  EMP1001  2026-07";
        MaskResult r = masker.mask(text);
        assertThat(r.maskedText()).isEqualTo(text);
        assertThat(r.total()).isZero();
    }

    @Test
    void wholeSlipKeepsEveryNumberTheExtractorNeeds() {
        MaskResult r = masker.mask(PRIYA_SLIP);

        assertThat(r.maskedText())
                .doesNotContain("ZZZPZ0001Z")
                .doesNotContain("0000 0000 0001")
                .contains("PAN           : [PAN]")
                .contains("Bank A/c No.  : [ACCOUNT_NUMBER]")
                .contains("37,100", "14,800", "39,220", "92,720", "8,720", "Net Pay: 84,000", "JULY 2026")
                .contains("Employee Name : Priya Sharma"); // names are a POC 8 design question, see below
        assertThat(r.count(PiiType.PAN)).isEqualTo(1);
        assertThat(r.count(PiiType.ACCOUNT_NUMBER)).isEqualTo(1);
        assertThat(r.total()).isEqualTo(2);
    }

    // ---------- behaviour ----------

    @Test
    void masksEveryOccurrenceAndCountsThem() {
        MaskResult r = masker.mask("PAN ZZZPZ0001Z, again ZZZPZ0001Z, other ABCDE1234F");
        assertThat(r.maskedText()).isEqualTo("PAN [PAN], again [PAN], other [PAN]");
        assertThat(r.count(PiiType.PAN)).isEqualTo(3);
    }

    @Test
    void maskingTwiceChangesNothing() {
        String once = masker.mask(PRIYA_SLIP).maskedText();
        MaskResult twice = masker.mask(once);
        assertThat(twice.maskedText()).isEqualTo(once);
        assertThat(twice.total()).isZero();
    }

    @Test
    void textWithoutPiiIsUnchanged() {
        MaskResult r = masker.mask("What is the maximum FOIR for a salaried applicant?");
        assertThat(r.maskedText()).isEqualTo("What is the maximum FOIR for a salaried applicant?");
        assertThat(r.total()).isZero();
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(masker.mask(null).maskedText()).isEmpty();
        assertThat(masker.mask("   ").maskedText()).isEqualTo("   ");
    }
}
