package com.loanpilot.orchestrator.privacy;

import java.util.Map;

/**
 * The masked text plus how many values of each type were replaced. The counts go to the audit log;
 * the original values never do.
 */
public record MaskResult(String maskedText, Map<PiiType, Integer> counts) {

    public int count(PiiType type) {
        return counts.getOrDefault(type, 0);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
