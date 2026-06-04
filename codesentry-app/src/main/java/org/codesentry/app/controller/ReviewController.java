package org.codesentry.app.controller;

import org.codesentry.app.db.AnalyzedFile;
import org.codesentry.app.db.AnalyzedFileRepository;
import org.codesentry.app.db.PrFindingRepository;
import org.codesentry.app.db.PrReview;
import org.codesentry.app.db.PrReviewRepository;
import org.codesentry.app.dto.ReviewRequest;
import org.codesentry.app.service.AnalysisPipeline;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class ReviewController {

    private static final Pattern GITHUB_PR_PATTERN =
            Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)");

    private final AnalysisPipeline analysisPipeline;
    private final PrReviewRepository prReviewRepository;
    private final PrFindingRepository prFindingRepository;
    private final AnalyzedFileRepository analyzedFileRepository;

    private final ConcurrentHashMap<String, Instant> rateLimiter = new ConcurrentHashMap<>();
    private static final Duration RATE_LIMIT_DURATION = Duration.ofSeconds(10);

    public ReviewController(AnalysisPipeline analysisPipeline,
                            PrReviewRepository prReviewRepository,
                            PrFindingRepository prFindingRepository,
                            AnalyzedFileRepository analyzedFileRepository) {
        this.analysisPipeline = analysisPipeline;
        this.prReviewRepository = prReviewRepository;
        this.prFindingRepository = prFindingRepository;
        this.analyzedFileRepository = analyzedFileRepository;
    }

    /** Trigger a new analysis — either from a GitHub PR URL or pasted code. */
    @PostMapping("/review")
    public ResponseEntity<Map<String, Object>> review(@RequestBody ReviewRequest request, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        Instant now = Instant.now();
        Instant lastRequestTime = rateLimiter.get(clientIp);
        if (lastRequestTime != null && Duration.between(lastRequestTime, now).compareTo(RATE_LIMIT_DURATION) < 0) {
            return ResponseEntity.status(429).body(Map.of("error", "Too Many Requests. Please wait 10 seconds before trying again."));
        }
        rateLimiter.put(clientIp, now);

        try {
            if ("github".equals(request.getType())) {
                String url = request.getGithubUrl();
                if (url == null || url.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "GitHub PR URL is required."));
                }
                Matcher m = GITHUB_PR_PATTERN.matcher(url.trim());
                if (!m.find()) {
                    return ResponseEntity.badRequest().body(
                            Map.of("error", "Invalid URL. Expected: https://github.com/owner/repo/pull/123"));
                }
                String owner = m.group(1);
                String repo = m.group(2);
                int prNumber = Integer.parseInt(m.group(3));
                Map<String, Object> result = analysisPipeline.analyzeGitHubPr(owner, repo, prNumber);
                return ResponseEntity.ok(result);

            } else if ("code".equals(request.getType())) {
                String code = request.getCode();
                if (code == null || code.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Code is required."));
                }
                String fileName = (request.getFileName() != null && !request.getFileName().isBlank())
                        ? request.getFileName() : "PastedCode.java";
                Map<String, Object> result = analysisPipeline.analyzeCode(code, fileName);
                return ResponseEntity.ok(result);

            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "type must be 'github' or 'code'."));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Return all past review runs with their finding counts. */
    @GetMapping("/reviews")
    public List<Map<String, Object>> getReviews() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PrReview review : prReviewRepository.findAll()) {
            long count = prFindingRepository.findByReviewId(review.getId()).size();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", review.getId());
            entry.put("repoName", review.getRepoName());
            entry.put("prNumber", review.getPrNumber());
            entry.put("commitSha", review.getCommitSha());
            entry.put("analyzedAt", review.getAnalyzedAt().toString());
            entry.put("status", review.getStatus());
            entry.put("findingCount", count);
            result.add(entry);
        }
        result.sort((a, b) -> Long.compare((Long) b.get("id"), (Long) a.get("id")));
        return result;
    }

    /** Return all findings for a specific review. */
    @GetMapping("/reviews/{id}/findings")
    public ResponseEntity<?> getFindings(@PathVariable("id") Long id) {
        if (!prReviewRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(prFindingRepository.findByReviewId(id));
    }

    /** Return all files associated with a specific review. */
    @GetMapping("/reviews/{id}/files")
    public ResponseEntity<?> getFiles(@PathVariable("id") Long id) {
        if (!prReviewRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, String>> files = analyzedFileRepository.findByReviewId(id).stream()
                .map(af -> Map.of("filePath", af.getFilePath()))
                .toList();
        return ResponseEntity.ok(files);
    }

    /** Return the content of a specific file for a review. */
    @GetMapping("/reviews/{id}/files/content")
    public ResponseEntity<?> getFileContent(@PathVariable("id") Long id, @RequestParam("filePath") String filePath) {
        Optional<AnalyzedFile> file = analyzedFileRepository.findByReviewIdAndFilePath(id, filePath);
        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("filePath", filePath, "content", file.get().getContent()));
    }
}
