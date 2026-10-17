package com.loanpilot.orchestrator.policy;

import java.util.List;

/**
 * One piece of the policy that gets its own embedding (vector) in Qdrant.
 *
 * @param section   the clause this chunk starts in, e.g. "4.2". Used for citations
 * @param sections  every clause the chunk overlaps. Always one for clause chunking; can be several for
 *                  fixed-size chunking, which cuts wherever the character count says
 * @param title     heading of the starting clause, e.g. "Maximum FOIR for salaried applicants"
 * @param text      what gets embedded AND what the LLM later reads
 */
public record PolicyChunk(String section, List<String> sections, String title, String text) {
}
