package com.loanpilot.bureau;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Repository;

/**
 * In-memory mock bureau. Priya, Rahul and Arjun match loan-orchestrator's ApplicantDirectory (same ids, scores
 * and existing EMIs), so POC 5 can swap the local tool for this server and get the same answers.
 *
 * Each record exercises a different path:
 * - APP-001 Priya: clean file, good score (the happy path in docs/architecture.md)
 * - APP-002 Rahul: low score, one 90+ DPD account, many recent enquiries
 * - APP-003 Arjun: no credit history (thin file, null score)
 * - APP-004 Sneha: excellent score, no red flags
 * - APP-005 Karan: borderline score, credit hungry (lots of enquiries), no DPD
 */
@Repository
public class BureauRepository {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 10, 1);

    private final List<BureauRecord> records = List.of(
            new BureauRecord("APP-001", "Priya Sharma", "ZZZPZ0001Z", 762, 2, new BigDecimal("20000"), 0, 1, REPORT_DATE),
            new BureauRecord("APP-002", "Rahul Verma", "ZZZPZ0002Z", 641, 4, new BigDecimal("18000"), 1, 5, REPORT_DATE),
            new BureauRecord("APP-003", "Arjun Nair", "ZZZPZ0003Z", null, 0, BigDecimal.ZERO, 0, 0, REPORT_DATE),
            new BureauRecord("APP-004", "Sneha Reddy", "ZZZPZ0004Z", 805, 1, new BigDecimal("12500"), 0, 0, REPORT_DATE),
            new BureauRecord("APP-005", "Karan Mehta", "ZZZPZ0005Z", 698, 3, new BigDecimal("26000"), 0, 7, REPORT_DATE));

    public Optional<BureauRecord> findByApplicantId(String applicantId) {
        return records.stream().filter(r -> r.applicantId().equalsIgnoreCase(applicantId.trim())).findFirst();
    }

    public Optional<BureauRecord> findByPan(String pan) {
        return records.stream().filter(r -> r.pan().equalsIgnoreCase(pan.trim())).findFirst();
    }

    /** Matches the full name or the first name, ignoring case ("priya" finds Priya Sharma). */
    public Optional<BureauRecord> findByName(String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return records.stream()
                .filter(r -> r.name().toLowerCase(Locale.ROOT).equals(wanted)
                        || r.name().toLowerCase(Locale.ROOT).split(" ")[0].equals(wanted))
                .findFirst();
    }

    public List<BureauRecord> all() {
        return records;
    }
}
