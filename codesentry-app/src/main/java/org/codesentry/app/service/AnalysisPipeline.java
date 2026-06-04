package org.codesentry.app.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.codesentry.app.db.*;
import org.codesentry.app.github.GitHubClientService;
import org.codesentry.app.llm.LlmExplainerService;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.analysis.MultiLanguageStaticAnalyzer;
import org.codesentry.core.model.Finding;
import org.codesentry.core.rule.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class AnalysisPipeline {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisPipeline.class);

    private final GitHubClientService gitHubClientService;
    private final LlmExplainerService llmExplainerService;
    private final PrReviewRepository prReviewRepository;
    private final PrFindingRepository prFindingRepository;
    private final AnalyzedFileRepository analyzedFileRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final List<Rule> rules;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AnalysisPipeline(GitHubClientService gitHubClientService,
                            LlmExplainerService llmExplainerService,
                            PrReviewRepository prReviewRepository,
                            PrFindingRepository prFindingRepository,
                            AnalyzedFileRepository analyzedFileRepository,
                            RedisTemplate<String, Object> redisTemplate) {
        this.gitHubClientService = gitHubClientService;
        this.llmExplainerService = llmExplainerService;
        this.prReviewRepository = prReviewRepository;
        this.prFindingRepository = prFindingRepository;
        this.analyzedFileRepository = analyzedFileRepository;
        this.redisTemplate = redisTemplate;
        this.rules = List.of(
                new ResourceLeakRule(),
                new NullSafetyRule(),
                new ConcurrencyRule(),
                new NPlusOneQueryRule(),
                new SqlInjectionRule(),
                new SecretDetectionRule(),
                new LoopBoundRule()
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API: analyze raw Java/C++/Python/JS code pasted by the user
    // ──────────────────────────────────────────────────────────────────────────
    public Map<String, Object> analyzeCode(String code, String fileName) {
        logger.info("[API] Analyzing pasted code: {}", fileName);
        try {
            Map<String, CompilationUnit> asts = new HashMap<>();
            Map<String, String> contents = new HashMap<>();
            contents.put(fileName, code);

            if (fileName.endsWith(".java")) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(code);
                    asts.put(fileName, cu);
                } catch (Exception e) {
                    logger.warn("Failed to parse Java AST, falling back to pattern matching: {}", e.getMessage());
                }
            }

            AnalysisContext ctx = AnalysisContext.builder()
                    .filePaths(List.of(fileName))
                    .parsedAsts(asts)
                    .fileContents(contents)
                    .changedLines(null)
                    .build();

            return executeAndPersist(ctx, "direct/" + fileName, 0, "manual");
        } catch (Exception e) {
            logger.error("Failed to analyze code", e);
            return Map.of("error", e.getMessage(), "findings", List.of());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API: analyze a real GitHub PR
    // ──────────────────────────────────────────────────────────────────────────
    public Map<String, Object> analyzeGitHubPr(String owner, String repo, int prNumber) {
        String repoName = owner + "/" + repo;
        logger.info("[API] Analyzing GitHub PR #{} on {}", prNumber, repoName);

        // 1. Get head SHA & branch from GitHub API
        String headSha = "unknown";
        String headBranch = "main";
        try {
            String prApiUrl = String.format("https://api.github.com/repos/%s/pulls/%d", repoName, prNumber);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(prApiUrl))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "CodeSentry").GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                headSha = extractJson(body, "\"sha\"");
                headBranch = extractJson(body, "\"ref\"");
            }
        } catch (Exception e) {
            logger.warn("Could not fetch PR metadata: {}", e.getMessage());
        }

        // 2. Get changed files
        List<String> codeFiles = new ArrayList<>();
        try {
            String filesUrl = String.format("https://api.github.com/repos/%s/pulls/%d/files", repoName, prNumber);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(filesUrl))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "CodeSentry").GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                String[] entries = body.split("\"filename\"\\s*:\\s*\"");
                for (int i = 1; i < entries.length; i++) {
                    String f = entries[i].substring(0, entries[i].indexOf('"'));
                    if (GitHubClientService.isSupportedFile(f)) {
                        codeFiles.add(f);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not fetch PR files: {}", e.getMessage());
        }

        if (codeFiles.isEmpty()) {
            logger.info("No supported code files found in PR #{}", prNumber);
            PrReview review = new PrReview(repoName, prNumber, headSha, Instant.now(), "NO_CODE_FILES");
            prReviewRepository.save(review);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reviewId", review.getId());
            result.put("source", repoName + "#" + prNumber);
            result.put("message", "No supported code files found in this PR.");
            result.put("findings", List.of());
            return result;
        }

        // 3. Fetch and parse each code file
        Map<String, CompilationUnit> asts = new HashMap<>();
        Map<String, String> contents = new HashMap<>();
        for (String filePath : codeFiles) {
            String rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s",
                    repoName, headBranch, filePath);
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(rawUrl))
                        .header("User-Agent", "CodeSentry").GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    String rawContent = resp.body();
                    contents.put(filePath, rawContent);
                    if (filePath.endsWith(".java")) {
                        try {
                            CompilationUnit cu = StaticJavaParser.parse(rawContent);
                            asts.put(filePath, cu);
                        } catch (Exception je) {
                            logger.warn("Failed parsing Java AST for {}, using patterns: {}", filePath, je.getMessage());
                        }
                    }
                    logger.info("Fetched: {}", filePath);
                }
            } catch (Exception e) {
                logger.warn("Could not fetch {}: {}", filePath, e.getMessage());
            }
        }

        AnalysisContext ctx = AnalysisContext.builder()
                .filePaths(new ArrayList<>(contents.keySet()))
                .parsedAsts(asts)
                .fileContents(contents)
                .changedLines(null)
                .build();

        return executeAndPersist(ctx, repoName, prNumber, headSha);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Existing webhook pipeline (async, posts back to GitHub)
    // ──────────────────────────────────────────────────────────────────────────
    public void runPipeline(String repoName, int prNumber, String commitSha) {
        long startTime = System.currentTimeMillis();
        logger.info("[STAGE 1: INGEST] Fetching diff for PR #{} on {}", prNumber, repoName);

        String diffText = gitHubClientService.fetchPrDiff(repoName, prNumber);
        GitHubClientService.DiffInfo diffInfo = gitHubClientService.parseDiff(diffText);

        if (diffInfo.changedFiles().isEmpty()) {
            logger.info("No code files changed. Skipping.");
            return;
        }

        logger.info("[STAGE 2: ANALYZE] Parsing AST and running rules");
        Map<String, CompilationUnit> parsedAsts = new HashMap<>();
        Map<String, String> contents = new HashMap<>();
        for (String filePath : diffInfo.changedFiles()) {
            String content = loadFileContentOrFetch(repoName, commitSha, filePath);
            if (content != null && !content.isBlank()) {
                contents.put(filePath, content);
                if (filePath.endsWith(".java")) {
                    try {
                        CompilationUnit cu = getOrParseAst(filePath, content);
                        if (cu != null) parsedAsts.put(filePath, cu);
                    } catch (Exception e) {
                        logger.warn("Failed to parse Java AST for {}, using patterns: {}", filePath, e.getMessage());
                    }
                }
            }
        }

        AnalysisContext context = AnalysisContext.builder()
                .filePaths(diffInfo.changedFiles())
                .changedLines(diffInfo.changedLines())
                .parsedAsts(parsedAsts)
                .fileContents(contents)
                .build();

        List<Finding> allFindings = runRules(context);

        List<Finding> relevantFindings = allFindings.stream().filter(f -> {
            if (context.isLineChanged(f.getFile(), f.getLine())) return true;
            if (f.getDataflowPath() != null) {
                return f.getDataflowPath().stream().anyMatch(s -> context.isLineChanged(s.getFile(), s.getLine()));
            }
            return false;
        }).toList();

        logger.info("[STAGE 3: EXPLAIN] Enriching {} findings", relevantFindings.size());

        PrReview review = new PrReview(repoName, prNumber, commitSha, Instant.now(), "SUCCESS");
        prReviewRepository.save(review);

        persistAnalyzedFiles(context, review.getId());

        List<GitHubClientService.PrComment> inlineComments = new ArrayList<>();
        StringBuilder summary = new StringBuilder("# CodeSentry Static Analysis Report\n\n");

        if (relevantFindings.isEmpty()) {
            summary.append("🎉 **No issues found!**");
        } else {
            for (Finding f : relevantFindings) {
                LlmExplainerService.LlmEnrichment e = llmExplainerService.enrichFinding(f);
                prFindingRepository.save(new PrFinding(review.getId(), f.getRuleId(),
                        f.getSeverity().toString(), f.getFile(), f.getLine(),
                        f.getCodeSnippet(), f.getMessage(), e.rationale(), e.fix(), e.priorityScore()));

                summary.append(String.format("- **%s** `%s:L%d` (Priority %d/10)\n  %s\n\n",
                        f.getRuleId(), f.getFile(), f.getLine(), e.priorityScore(), e.rationale()));

                String body = String.format("### CodeSentry: %s\n**Rule**: %s | **Priority**: %d/10\n\n%s\n\n**Fix**:\n```\n%s\n```",
                        f.getSeverity(), f.getRuleId(), e.priorityScore(), e.rationale(), e.fix());
                inlineComments.add(new GitHubClientService.PrComment(f.getFile(), f.getLine(), body));
            }
        }

        gitHubClientService.postPrReview(repoName, prNumber, commitSha, summary.toString(), inlineComments);
        logger.info("Pipeline completed in {} ms", System.currentTimeMillis() - startTime);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Run all rules, persist results, return JSON-friendly map. */
    private Map<String, Object> executeAndPersist(AnalysisContext ctx, String source, int prNumber, String commitSha) {
        long start = System.currentTimeMillis();
        List<Finding> findings = runRules(ctx);
        logger.info("Rules produced {} findings in {} ms", findings.size(), System.currentTimeMillis() - start);

        PrReview review = new PrReview(source, prNumber, commitSha, Instant.now(), "SUCCESS");
        prReviewRepository.save(review);

        persistAnalyzedFiles(ctx, review.getId());

        List<Map<String, Object>> findingResults = new ArrayList<>();
        for (Finding f : findings) {
            LlmExplainerService.LlmEnrichment enr = llmExplainerService.enrichFinding(f);
            prFindingRepository.save(new PrFinding(review.getId(), f.getRuleId(),
                    f.getSeverity().toString(), f.getFile(), f.getLine(),
                    f.getCodeSnippet(), f.getMessage(), enr.rationale(), enr.fix(), enr.priorityScore()));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleId", f.getRuleId());
            item.put("severity", f.getSeverity().toString());
            item.put("file", f.getFile());
            item.put("line", f.getLine());
            item.put("message", f.getMessage());
            item.put("codeSnippet", f.getCodeSnippet());
            item.put("rationale", enr.rationale());
            item.put("fix", enr.fix());
            item.put("priorityScore", enr.priorityScore());
            findingResults.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviewId", review.getId());
        result.put("source", source);
        result.put("analyzedAt", review.getAnalyzedAt().toString());
        result.put("findingCount", findingResults.size());
        result.put("findings", findingResults);
        return result;
    }

    private void persistAnalyzedFiles(AnalysisContext ctx, Long reviewId) {
        if (ctx.getFileContents() != null) {
            for (Map.Entry<String, String> entry : ctx.getFileContents().entrySet()) {
                AnalyzedFile file = new AnalyzedFile(reviewId, entry.getKey(), entry.getValue());
                analyzedFileRepository.save(file);
            }
        }
    }

    private List<Finding> runRules(AnalysisContext ctx) {
        List<Finding> all = new ArrayList<>();

        // 1. Run core Java rules on parsed Java ASTs
        if (ctx.getParsedAsts() != null && !ctx.getParsedAsts().isEmpty()) {
            for (Rule rule : rules) {
                long t = System.currentTimeMillis();
                List<Finding> found = rule.analyze(ctx);
                logger.info("Rule {} → {} issues in {} ms", rule.getId(), found.size(), System.currentTimeMillis() - t);
                all.addAll(found);
            }
        }

        // 2. Run multi-language pattern rules on all other files
        if (ctx.getFileContents() != null) {
            for (Map.Entry<String, String> entry : ctx.getFileContents().entrySet()) {
                String path = entry.getKey();
                if (!path.endsWith(".java")) {
                    long t = System.currentTimeMillis();
                    List<Finding> found = MultiLanguageStaticAnalyzer.analyze(path, entry.getValue());
                    logger.info("MultiLanguageStaticAnalyzer for {} → {} issues in {} ms", path, found.size(), System.currentTimeMillis() - t);
                    all.addAll(found);
                }
            }
        }

        return all;
    }

    private String loadFileContentOrFetch(String repoName, String commitSha, String filePath) {
        String demo = loadFileContent(filePath);
        if (!demo.isBlank()) return demo;

        String rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s", repoName, commitSha, filePath);
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(rawUrl))
                    .header("User-Agent", "CodeSentry").GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private CompilationUnit getOrParseAst(String filePath, String content) {
        String cacheKey = "ast:" + filePath;
        try {
            String cached = (String) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) return StaticJavaParser.parse(cached);
        } catch (Exception ignored) {}

        try {
            if (content == null || content.isBlank()) return null;
            CompilationUnit cu = StaticJavaParser.parse(content);
            try { redisTemplate.opsForValue().set(cacheKey, content, 1, TimeUnit.HOURS); } catch (Exception ignored) {}
            return cu;
        } catch (Exception e) {
            logger.error("Failed to parse AST for {}", filePath, e);
            return null;
        }
    }

    private String loadFileContent(String filePath) {
        if (filePath.endsWith("DemoClass.java")) {
            return "package org.codesentry.demo;\n" +
                    "import java.io.FileInputStream;\nimport java.io.IOException;\n" +
                    "import java.util.ArrayList;\nimport java.util.List;\n" +
                    "public class DemoClass {\n" +
                    "    private List<String> dataList;\n" +
                    "    public List<String> getDataList() { return dataList; }\n" +
                    "    public void leakResource() throws IOException {\n" +
                    "        FileInputStream fis = new FileInputStream(\"test.txt\");\n" +
                    "        int data = fis.read(); System.out.println(data);\n    }\n" +
                    "    public void nullDereference() {\n" +
                    "        String str = null; System.out.println(str.length());\n    }\n" +
                    "    public void queryInLoop() {\n" +
                    "        List<String> ids = new ArrayList<>();\n" +
                    "        for (String id : ids) { userRepository.findById(id); }\n    }\n}\n";
        }
        return "";
    }

    /** Quick JSON field extractor (no Jackson needed for simple strings). */
    private static String extractJson(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return "unknown";
        int start = json.indexOf('"', idx + key.length() + 1) + 1;
        int end = json.indexOf('"', start);
        return (start > 0 && end > start) ? json.substring(start, end) : "unknown";
    }
}
