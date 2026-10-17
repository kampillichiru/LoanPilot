package com.loanpilot.orchestrator.advise;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/advise")
public class AdviseController {

    private final AdviseService adviseService;

    public AdviseController(AdviseService adviseService) {
        this.adviseService = adviseService;
    }

    /** Quick browser/curl test: /api/advise?q=Can Priya afford 5L for 3 years? */
    @GetMapping
    public AdviseResponse advise(@RequestParam("q") String question,
                                 @RequestParam(value = "applicantId", required = false) String applicantId) {
        return adviseService.advise(new AdviseRequest(question, applicantId));
    }

    @PostMapping
    public AdviseResponse advise(@Valid @RequestBody AdviseRequest request) {
        return adviseService.advise(request);
    }
}
