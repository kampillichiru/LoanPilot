package com.loanpilot.orchestrator.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds policy citations in LLM output. Shared by policy answers (POC 6) and credit memos (POC 7). */
public final class Citations {

    /** "§4.2", "§ 4.2", "Section 4.2", "clause 4.2" all count as a citation of 4.2. */
    private static final Pattern CITATION = Pattern.compile("(?:§|\\b[Ss]ection|\\b[Cc]lause)\\s*(\\d+\\.\\d+)");

    private Citations() {
    }

    public static List<String> find(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return new ArrayList<>(found);
    }
}
