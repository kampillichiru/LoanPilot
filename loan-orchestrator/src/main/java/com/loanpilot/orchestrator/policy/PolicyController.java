package com.loanpilot.orchestrator.policy;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    private final PolicyIngestService ingestService;
    private final PolicyService policyService;

    public PolicyController(PolicyIngestService ingestService, PolicyService policyService) {
        this.ingestService = ingestService;
        this.policyService = policyService;
    }

    /** (Re)build the index: chunk the policy, embed every chunk, store in Qdrant. Run once before asking. */
    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestParam(value = "strategy", defaultValue = "CLAUSE") PolicyChunker.Strategy strategy,
                                 @RequestParam(value = "chunkSize", defaultValue = "500") int chunkSize) {
        return ingestService.ingest(strategy, chunkSize);
    }

    /** Retrieval only (no LLM, ~1 s): which clauses would the model see? */
    @GetMapping("/search")
    public List<RetrievedClause> search(@RequestParam("q") String question,
                                        @RequestParam(value = "topK", required = false) Integer topK,
                                        @RequestParam(value = "minScore", required = false) Double minScore) {
        return policyService.search(new PolicyAskRequest(question, topK, minScore));
    }

    /** Quick browser/curl test: /api/policy/ask?q=What is the maximum FOIR? */
    @GetMapping("/ask")
    public PolicyAnswer ask(@RequestParam("q") String question,
                            @RequestParam(value = "topK", required = false) Integer topK,
                            @RequestParam(value = "minScore", required = false) Double minScore) {
        return policyService.ask(new PolicyAskRequest(question, topK, minScore));
    }

    @PostMapping("/ask")
    public PolicyAnswer ask(@Valid @RequestBody PolicyAskRequest request) {
        return policyService.ask(request);
    }

    @ExceptionHandler(PolicyIndexUnavailableException.class)
    public ProblemDetail indexUnavailable(PolicyIndexUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()
                + ". Is Qdrant running (gRPC port 6334)? Have you run POST /api/policy/ingest?");
        problem.setTitle("Policy index unavailable");
        return problem;
    }
}
