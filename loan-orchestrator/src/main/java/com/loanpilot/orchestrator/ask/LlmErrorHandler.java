package com.loanpilot.orchestrator.ask;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/** Turns "Ollama is not running" into a clear 503 instead of a stack trace. */
@RestControllerAdvice
public class LlmErrorHandler {

    @ExceptionHandler(ResourceAccessException.class)
    public ProblemDetail llmUnavailable(ResourceAccessException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Cannot reach the LLM at spring.ai.ollama.base-url. Is Ollama running? Try: ollama list");
        problem.setTitle("LLM unavailable");
        return problem;
    }
}
