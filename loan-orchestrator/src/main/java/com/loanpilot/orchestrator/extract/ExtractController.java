package com.loanpilot.orchestrator.extract;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/extract")
public class ExtractController {

    private final ExtractService extractService;

    public ExtractController(ExtractService extractService) {
        this.extractService = extractService;
    }

    /** 200 = parsed and valid. 422 = model answered, but the result is unusable (see errors + rawOutput). */
    @PostMapping(value = "/salary-slip", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExtractResponse> extract(@Valid @RequestBody ExtractRequest request) {
        return toResponse(extractService.extract(request));
    }

    /** Paste a slip as-is: curl --data-binary @slip-01.txt -H "Content-Type: text/plain" ... */
    @PostMapping(value = "/salary-slip", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<ExtractResponse> extractPlain(@RequestBody String text,
                                                        @RequestParam(value = "nativeSchema", required = false) Boolean nativeSchema) {
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return toResponse(extractService.extract(new ExtractRequest(text, nativeSchema)));
    }

    private static ResponseEntity<ExtractResponse> toResponse(ExtractResponse response) {
        return ResponseEntity.status(response.valid() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(response);
    }
}
