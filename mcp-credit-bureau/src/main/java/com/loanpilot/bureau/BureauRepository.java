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
 *
 * POC 8 adds APP-006 .. APP-020 for the 20-applicant golden set (data-generator/applicants.py), each built to sit
 * on one rule boundary: FOIR exactly 50%, CIBIL exactly 700, 4 vs 5 enquiries, thin files, the preferential-rate
 * edge. APP-019 is deliberately absent: the "not found at the bureau" case.
 */
@Repository
public class BureauRepository {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 10, 1);

    private final List<BureauRecord> records = List.of(
            new BureauRecord("APP-001", "Priya Sharma", "ZZZPZ0001Z", 762, 2, new BigDecimal("20000"), 0, 1, REPORT_DATE),
            new BureauRecord("APP-002", "Rahul Verma", "ZZZPZ0002Z", 641, 4, new BigDecimal("18000"), 1, 5, REPORT_DATE),
            new BureauRecord("APP-003", "Arjun Nair", "ZZZPZ0003Z", null, 0, BigDecimal.ZERO, 0, 0, REPORT_DATE),
            new BureauRecord("APP-004", "Sneha Reddy", "ZZZPZ0004Z", 805, 1, new BigDecimal("12500"), 0, 0, REPORT_DATE),
            new BureauRecord("APP-005", "Karan Mehta", "ZZZPZ0005Z", 698, 3, new BigDecimal("26000"), 0, 7, REPORT_DATE),
            // POC 8 golden set
            new BureauRecord("APP-006", "Ananya Iyer", "ZZZPZ0006Z", 735, 2, new BigDecimal("13393"), 0, 1, REPORT_DATE),
            new BureauRecord("APP-007", "Aditya Kulkarni", "ZZZPZ0007Z", 735, 2, new BigDecimal("13394"), 0, 1, REPORT_DATE),
            new BureauRecord("APP-008", "Kavya Menon", "ZZZPZ0008Z", 700, 1, new BigDecimal("8000"), 0, 2, REPORT_DATE),
            new BureauRecord("APP-009", "Meera Joshi", "ZZZPZ0009Z", 720, 2, new BigDecimal("10000"), 0, 4, REPORT_DATE),
            new BureauRecord("APP-010", "Karthik Rao", "ZZZPZ0010Z", 730, 2, new BigDecimal("10000"), 0, 5, REPORT_DATE),
            new BureauRecord("APP-011", "Deepa Pillai", "ZZZPZ0011Z", 745, 0, BigDecimal.ZERO, 0, 1, REPORT_DATE),
            new BureauRecord("APP-012", "Suresh Patil", "ZZZPZ0012Z", 760, 0, BigDecimal.ZERO, 0, 0, REPORT_DATE),
            new BureauRecord("APP-013", "Farhan Qureshi", "ZZZPZ0013Z", null, 0, BigDecimal.ZERO, 0, 0, REPORT_DATE),
            new BureauRecord("APP-014", "Lakshmi Das", "ZZZPZ0014Z", null, 0, BigDecimal.ZERO, 0, 0, REPORT_DATE),
            new BureauRecord("APP-015", "Nikhil Bansal", "ZZZPZ0015Z", 750, 1, new BigDecimal("10000"), 0, 1, REPORT_DATE),
            new BureauRecord("APP-016", "Pooja Kapoor", "ZZZPZ0016Z", 790, 2, new BigDecimal("18000"), 0, 2, REPORT_DATE),
            new BureauRecord("APP-017", "Imran Sheikh", "ZZZPZ0017Z", 770, 1, new BigDecimal("5000"), 0, 1, REPORT_DATE),
            new BureauRecord("APP-018", "Harish Chandra", "ZZZPZ0018Z", 715, 2, new BigDecimal("8000"), 0, 2, REPORT_DATE),
            new BureauRecord("APP-020", "Rohit Gupta", "ZZZPZ0020Z", 728, 1, new BigDecimal("9000"), 0, 1, REPORT_DATE));

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
