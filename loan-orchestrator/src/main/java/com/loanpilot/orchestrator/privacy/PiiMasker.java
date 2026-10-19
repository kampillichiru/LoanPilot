package com.loanpilot.orchestrator.privacy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Replaces personal data with tokens like "[PAN]" before text is sent to an LLM (CLAUDE.md rule 2).
 *
 * Design choices:
 * - One-way: the original value is not kept anywhere. Nothing we ask the LLM needs the PAN back, and a mapping
 *   table would itself be a store of PII.
 * - Over-mask rather than under-mask: an unlabelled 12-digit number starting 2-9 is treated as an Aadhaar, an
 *   unlabelled 10-digit number starting 6-9 as a phone. Masking a harmless number costs a little accuracy;
 *   leaking an Aadhaar is a compliance incident.
 * - Order matters: a labelled bank account ("A/c No. 0000 0000 0001") is matched before Aadhaar, because the two
 *   look identical once the label is gone.
 * - Money survives: amounts use commas ("37,100", "1,25,000"), which none of the patterns accept inside a number.
 * - Names are NOT masked. The workflow compares names across slips; masking them needs consistent tokens
 *   (same name -> same token), a later step.
 */
@Component
public class PiiMasker {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 5 letters, 4 digits, 1 letter. Case-insensitive: a lower-case PAN is still a PAN. */
    private static final Pattern PAN = Pattern.compile("(?i)\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");

    /** A label ("A/c No.", "Account Number", "Acct") followed by 9-18 digits, spaces or hyphens allowed. */
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?i)(\\b(?:a/c|acct|account)\\b\\.?(?:\\s*(?:no|number|num)\\b\\.?)?\\s*[:#]?\\s*)(\\d(?:[ -]?\\d){8,17})(?!\\d)");

    /** 12 digits, first digit 2-9 (UIDAI never issues 0 or 1), as 4-4-4 with one consistent separator or none. */
    private static final Pattern AADHAAR = Pattern.compile("(?<!\\d)[2-9]\\d{3}([ -]?)\\d{4}\\1\\d{4}(?!\\d)");

    /** Indian mobile: optional +91 / 0, then 10 digits starting 6-9, optionally split 5+5. */
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+])(?:\\+91[ -]?|0)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)");

    public MaskResult mask(String text) {
        if (text == null) {
            return new MaskResult("", Map.of());
        }
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        String masked = text;
        masked = replace(masked, EMAIL, PiiType.EMAIL, false, counts);
        masked = replace(masked, PAN, PiiType.PAN, false, counts);
        masked = replace(masked, ACCOUNT, PiiType.ACCOUNT_NUMBER, true, counts);   // before Aadhaar
        masked = replace(masked, AADHAAR, PiiType.AADHAAR, false, counts);
        masked = replace(masked, PHONE, PiiType.PHONE, false, counts);
        return new MaskResult(masked, Collections.unmodifiableMap(counts));
    }

    /** keepLabel: the pattern's group 1 is a label ("A/c No. : ") that stays; only the value is replaced. */
    private static String replace(String text, Pattern pattern, PiiType type, boolean keepLabel,
                                  Map<PiiType, Integer> counts) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int found = 0;
        while (m.find()) {
            String replacement = keepLabel ? m.group(1) + type.token() : type.token();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            found++;
        }
        if (found == 0) {
            return text;
        }
        m.appendTail(sb);
        counts.merge(type, found, Integer::sum);
        return sb.toString();
    }

}
