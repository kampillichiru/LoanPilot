package com.loanpilot.orchestrator.privacy;

/** The kinds of personal data that must never reach an LLM (CLAUDE.md rule 2). */
public enum PiiType {
    PAN,
    AADHAAR,
    ACCOUNT_NUMBER,
    PHONE,
    EMAIL;

    /** What replaces a value in the masked text, e.g. "[PAN]". */
    public String token() {
        return "[" + name() + "]";
    }
}
