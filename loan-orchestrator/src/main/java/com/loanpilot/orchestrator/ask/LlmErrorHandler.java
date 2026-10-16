package com.loanpilot.orchestrator.ask;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/** Turns LLM failures into clear error responses instead of a bare 500. */
@RestControllerAdvice
public class LlmErrorHandler {

    /** Ollama is not running / not reachable. */
    @ExceptionHandler(ResourceAccessException.class)
    public ProblemDetail llmUnavailable(ResourceAccessException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Cannot reach the LLM at spring.ai.ollama.base-url. Is Ollama running? Try: ollama list");
        problem.setTitle("LLM unavailable");
        return problem;
    }

    /** Ollama answered but rejected the request, e.g. the model is not pulled. Not worth retrying. */
    @ExceptionHandler(NonTransientAiException.class)
    public ProblemDetail llmRejected(NonTransientAiException ex) {
        String detail = ex.getMessage();
        if (detail != null && detail.contains("not found")) {
            detail += ". Pull it with 'ollama pull <model>' or change spring.ai.ollama.chat.model to a model shown by 'ollama list'.";
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, detail);
        problem.setTitle("LLM rejected the request");
        return problem;
    }
}
