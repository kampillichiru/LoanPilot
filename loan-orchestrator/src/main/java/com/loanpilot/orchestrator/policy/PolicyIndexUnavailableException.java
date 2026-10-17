package com.loanpilot.orchestrator.policy;

/** Qdrant is down, or the policy was never ingested. Mapped to 503 with a hint in PolicyController. */
public class PolicyIndexUnavailableException extends RuntimeException {

    public PolicyIndexUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
