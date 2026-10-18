package com.loanpilot.orchestrator.application;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * One call: application + 3 salary slips in, decision + credit memo out. Always 200 when the application was
     * processed, whatever the outcome: APPROVE / REFER / REJECT is business data, not an HTTP error.
     */
    @PostMapping
    public ApplicationResult process(@Valid @RequestBody LoanApplicationRequest request) {
        return applicationService.process(request);
    }

    @ExceptionHandler(BureauUnavailableException.class)
    public ProblemDetail bureauUnavailable(BureauUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()
                + ". The application was not decided. Is mcp-credit-bureau running on 127.0.0.1:8082? Retry later.");
        problem.setTitle("Credit bureau unavailable");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid application");
        return problem;
    }
}
