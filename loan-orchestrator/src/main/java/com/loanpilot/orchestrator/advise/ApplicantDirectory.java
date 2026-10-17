package com.loanpilot.orchestrator.advise;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * In-memory mock of the bank's applicant data. POC 4 replaces the bureau part with an MCP server.
 *
 * Each applicant exercises a different path: Priya is the happy path from docs/architecture.md, Rahul has
 * a low score and a high existing EMI, and Arjun has no credit history (null score) so you can see how
 * the model handles a tool returning "no data".
 */
@Component
public class ApplicantDirectory {

    private final List<Applicant> applicants = List.of(
            new Applicant("APP-001", "Priya Sharma", "ZZZPZ0001Z",
                    new BigDecimal("85000"), new BigDecimal("20000"), 762),
            new Applicant("APP-002", "Rahul Verma", "ZZZPZ0002Z",
                    new BigDecimal("49650"), new BigDecimal("18000"), 641),
            new Applicant("APP-003", "Arjun Nair", "ZZZPZ0003Z",
                    new BigDecimal("62000"), BigDecimal.ZERO, null));

    public Optional<Applicant> findById(String id) {
        return applicants.stream().filter(a -> a.id().equalsIgnoreCase(id)).findFirst();
    }

    /** Matches the full name or the first name, ignoring case ("priya" finds Priya Sharma). */
    public Optional<Applicant> findByName(String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return applicants.stream()
                .filter(a -> a.name().toLowerCase(Locale.ROOT).equals(wanted)
                        || a.name().toLowerCase(Locale.ROOT).split(" ")[0].equals(wanted))
                .findFirst();
    }

    public List<Applicant> all() {
        return applicants;
    }
}
