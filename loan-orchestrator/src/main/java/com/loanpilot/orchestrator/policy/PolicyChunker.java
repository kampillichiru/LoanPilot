package com.loanpilot.orchestrator.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Splits the policy markdown into chunks. Two strategies, so you can compare them (README experiment 1):
 *
 * CLAUSE (default): one chunk per "### §4.2 ..." clause. A clause is the unit a credit officer cites, it is
 *   short (one idea), and it is never cut in half, so the retrieved text is always a complete rule.
 * FIXED: every N characters, ignoring structure. The generic approach. Watch it split a rule across two
 *   chunks, or glue two unrelated rules together, and the retrieval hit rate drop.
 *
 * Enrichment: each clause chunk starts with a breadcrumb ("4. Credit and affordability rules > §4.2 Maximum
 * FOIR for salaried applicants"). Spring AI embeds only the chunk TEXT, not its metadata, so the heading must
 * be in the text for the chapter context ("affordability") to count in similarity search.
 */
@Component
public class PolicyChunker {

    public enum Strategy { CLAUSE, FIXED }

    private static final Pattern CHAPTER = Pattern.compile("^## (\\d+)\\. (.+)$");
    private static final Pattern CLAUSE = Pattern.compile("^### §(\\d+\\.\\d+) (.+)$");

    /** One clause found in the document, with its position for the FIXED strategy's section mapping. */
    private record Clause(String section, String title, String chapter, String body, int start, int end) {
    }

    public List<PolicyChunk> chunk(String markdown, Strategy strategy, int chunkSize) {
        return strategy == Strategy.FIXED ? fixed(markdown, chunkSize) : byClause(markdown);
    }

    List<PolicyChunk> byClause(String markdown) {
        return parse(normalise(markdown)).stream()
                .map(c -> new PolicyChunk(c.section(), List.of(c.section()), c.title(),
                        c.chapter() + " > §" + c.section() + " " + c.title() + "\n" + c.body()))
                .toList();
    }

    List<PolicyChunk> fixed(String markdown, int chunkSize) {
        if (chunkSize < 50) {
            throw new IllegalArgumentException("chunkSize must be at least 50 characters");
        }
        String text = normalise(markdown);
        List<Clause> clauses = parse(text);
        if (clauses.isEmpty()) {
            return List.of();
        }
        // Start at the first chapter: the document title and disclaimer are not policy.
        int from = text.indexOf("\n## ") + 1;
        List<PolicyChunk> chunks = new ArrayList<>();
        for (int start = from; start < text.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, text.length());
            String piece = text.substring(start, end).strip();
            if (piece.isEmpty()) {
                continue;
            }
            Set<String> covered = new LinkedHashSet<>();
            Clause first = null;
            for (Clause c : clauses) {
                if (c.start() < end && c.end() > start) {
                    covered.add(c.section());
                    if (first == null) {
                        first = c;
                    }
                }
            }
            if (first == null) {   // only a chapter heading in this window: attach it to the next clause
                int windowStart = start;
                first = clauses.stream().filter(c -> c.start() >= windowStart).findFirst().orElse(clauses.getLast());
                covered.add(first.section());
            }
            chunks.add(new PolicyChunk(first.section(), List.copyOf(covered), first.title(), piece));
        }
        return chunks;
    }

    private static List<Clause> parse(String text) {
        List<Clause> clauses = new ArrayList<>();
        String chapter = null;
        String section = null;
        String title = null;
        int clauseStart = 0;
        StringBuilder body = new StringBuilder();

        int pos = 0;
        for (String line : text.split("\n", -1)) {
            Matcher ch = CHAPTER.matcher(line);
            Matcher cl = CLAUSE.matcher(line);
            if (ch.matches() || cl.matches()) {
                if (section != null) {
                    clauses.add(new Clause(section, title, chapter, body.toString().strip(), clauseStart, pos));
                    section = null;
                }
                if (ch.matches()) {
                    chapter = ch.group(1) + ". " + ch.group(2).strip();
                } else {
                    section = cl.group(1);
                    title = cl.group(2).strip();
                    clauseStart = pos;
                    body.setLength(0);
                }
            } else if (section != null) {
                body.append(line).append('\n');
            }
            pos += line.length() + 1;
        }
        if (section != null) {
            clauses.add(new Clause(section, title, chapter, body.toString().strip(), clauseStart, text.length()));
        }
        return clauses;
    }

    private static String normalise(String markdown) {
        return markdown.replace("\r\n", "\n");
    }
}
