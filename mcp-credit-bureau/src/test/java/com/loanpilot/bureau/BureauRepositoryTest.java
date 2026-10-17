package com.loanpilot.bureau;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BureauRepositoryTest {

    private final BureauRepository bureau = new BureauRepository();

    @Test
    void findsByIdPanAndName() {
        assertThat(bureau.findByApplicantId("app-001")).get().extracting(BureauRecord::cibilScore).isEqualTo(762);
        assertThat(bureau.findByPan("ZZZPZ0002Z")).get().extracting(BureauRecord::applicantId).isEqualTo("APP-002");
        assertThat(bureau.findByName("sneha")).get().extracting(BureauRecord::applicantId).isEqualTo("APP-004");
    }

    @Test
    void thinFileHasNoScore() {
        assertThat(bureau.findByApplicantId("APP-003")).get().extracting(BureauRecord::cibilScore).isNull();
    }

    @Test
    void unknownIsEmpty() {
        assertThat(bureau.findByApplicantId("APP-999")).isEmpty();
        assertThat(bureau.findByName("Vikram")).isEmpty();
    }

    @Test
    void allPansAreSynthetic() {
        // Rule 5: the ZZZPZ prefix marks test PANs; a real-looking PAN here is a mistake.
        assertThat(bureau.all()).allSatisfy(r -> assertThat(r.pan()).startsWith("ZZZPZ"));
    }
}
