package com.loanpilot.orchestrator.application;

/**
 * The bureau couldn't be asked. A technical failure, not a credit outcome: the application is not decided and
 * the caller gets a 503 to retry, rather than a REFER or REJECT that would look like a credit judgement.
 */
public class BureauUnavailableException extends RuntimeException {

    public BureauUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
