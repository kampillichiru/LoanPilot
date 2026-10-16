package com.loanpilot.orchestrator.ask;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ask")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    /** Quick browser/curl test: /api/ask?q=What is FOIR&temperature=0.0&seed=42 */
    @GetMapping
    public AskResponse ask(@RequestParam("q") String question,
                           @RequestParam(value = "temperature", required = false) Double temperature,
                           @RequestParam(value = "system", required = false) Boolean useSystemPrompt,
                           @RequestParam(value = "seed", required = false) Integer seed,
                           @RequestParam(value = "topK", required = false) Integer topK) {
        return askService.ask(new AskRequest(question, temperature, useSystemPrompt, seed, topK));
    }

    @PostMapping
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return askService.ask(request);
    }
}
