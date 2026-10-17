package com.loanpilot.orchestrator.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PolicyChunkerTest {

    static final String POLICY = """
            # Test Bank Policy

            > SYNTHETIC. Not real.

            ## 4. Credit and affordability rules

            ### §4.2 Maximum FOIR for salaried applicants
            For salaried applicants, FOIR must not exceed 50% of net monthly income.

            ### §4.3 Maximum FOIR for self-employed professionals
            For self-employed professionals, FOIR must not exceed 45%.

            ## 6. Pricing

            ### §6.1 Standard interest rate
            The standard interest rate is 12% per annum.
            """;

    private final PolicyChunker chunker = new PolicyChunker();

    @Test
    void clauseChunkingGivesOneChunkPerClauseWithBreadcrumb() {
        List<PolicyChunk> chunks = chunker.chunk(POLICY, PolicyChunker.Strategy.CLAUSE, 0);

        assertThat(chunks).extracting(PolicyChunk::section).containsExactly("4.2", "4.3", "6.1");
        PolicyChunk foir = chunks.getFirst();
        assertThat(foir.title()).isEqualTo("Maximum FOIR for salaried applicants");
        assertThat(foir.sections()).containsExactly("4.2");
        // breadcrumb first (so the chapter is embedded too), then the clause body, and nothing from §4.3
        assertThat(foir.text()).isEqualTo("""
                4. Credit and affordability rules > §4.2 Maximum FOIR for salaried applicants
                For salaried applicants, FOIR must not exceed 50% of net monthly income.""");
    }

    @Test
    void titleAndDisclaimerAreNotChunked() {
        List<PolicyChunk> chunks = chunker.chunk(POLICY, PolicyChunker.Strategy.CLAUSE, 0);

        assertThat(chunks).noneMatch(c -> c.text().contains("SYNTHETIC") || c.text().contains("Test Bank"));
    }

    @Test
    void windowsLineEndingsGiveTheSameChunks() {
        assertThat(chunker.chunk(POLICY.replace("\n", "\r\n"), PolicyChunker.Strategy.CLAUSE, 0))
                .isEqualTo(chunker.chunk(POLICY, PolicyChunker.Strategy.CLAUSE, 0));
    }

    @Test
    void fixedChunkingCutsAcrossClausesAndRecordsEverySectionItTouches() {
        List<PolicyChunk> chunks = chunker.chunk(POLICY, PolicyChunker.Strategy.FIXED, 150);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(c -> c.text().length() <= 150);
        // at least one chunk mixes two clauses: the weakness this strategy is here to demonstrate
        assertThat(chunks).anyMatch(c -> c.sections().size() > 1);
        // every clause is still covered by some chunk
        assertThat(chunks.stream().flatMap(c -> c.sections().stream()).distinct())
                .containsExactlyInAnyOrder("4.2", "4.3", "6.1");
    }

    @Test
    void tinyFixedChunksAreRejected() {
        assertThatThrownBy(() -> chunker.chunk(POLICY, PolicyChunker.Strategy.FIXED, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
