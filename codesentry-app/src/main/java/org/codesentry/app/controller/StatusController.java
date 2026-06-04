package org.codesentry.app.controller;

import org.codesentry.app.db.PrFindingRepository;
import org.codesentry.app.db.PrReviewRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class StatusController {

    private final PrReviewRepository prReviewRepository;
    private final PrFindingRepository prFindingRepository;

    public StatusController(PrReviewRepository prReviewRepository,
                            PrFindingRepository prFindingRepository) {
        this.prReviewRepository = prReviewRepository;
        this.prFindingRepository = prFindingRepository;
    }



    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        return Map.of(
                "status", "UP",
                "service", "CodeSentry Static Analysis Engine",
                "totalReviews", prReviewRepository.count(),
                "totalFindings", prFindingRepository.count(),
                "rules", new String[]{
                        "RULE-001-RESOURCE-LEAK",
                        "RULE-002-NULL-SAFETY",
                        "RULE-003-CONCURRENCY",
                        "RULE-004-NPLUSONE-QUERY"
                },
                "timestamp", Instant.now().toString()
        );
    }
}
