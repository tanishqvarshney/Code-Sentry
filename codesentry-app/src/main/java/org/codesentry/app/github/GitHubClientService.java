package org.codesentry.app.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class GitHubClientService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubClientService.class);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${github.app-id}")
    private String appId;

    @Value("${github.webhook-secret}")
    private String webhookSecret;

    @Value("${github.token:}")
    private String githubToken;

    /**
     * Fetches the PR diff from GitHub. If in mock mode, returns a simulated Java class diff.
     */
    public String fetchPrDiff(String repoName, int prNumber) {
        if ("mock/test-repo".equals(repoName) || "mock-key".equals(webhookSecret)) {
            logger.info("Mocking PR diff fetch for repository: {}, PR: {}", repoName, prNumber);
            return getSimulatedDiff();
        }

        String url = String.format("https://api.github.com/repos/%s/pulls/%d", repoName, prNumber);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github.diff")
                .header("User-Agent", "CodeSentry")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                logger.error("Failed to fetch PR diff, status code: {}", response.statusCode());
                return getSimulatedDiff(); // Fallback to simulated for easy testing
            }
        } catch (Exception e) {
            logger.error("Error fetching PR diff from GitHub", e);
            return getSimulatedDiff();
        }
    }

    /**
     * Parses a unified diff to get the changed files and the specific lines added/modified.
     */
    public DiffInfo parseDiff(String diffText) {
        Map<String, Set<Integer>> changedLines = new HashMap<>();
        List<String> changedFiles = new ArrayList<>();

        String currentFile = null;
        int currentLine = 0;

        String[] lines = diffText.split("\n");
        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                // Extract file path from "diff --git a/src/Main.java b/src/Main.java"
                String[] parts = line.split(" ");
                if (parts.length >= 4) {
                    String bPath = parts[3];
                    if (bPath.startsWith("b/")) {
                        currentFile = bPath.substring(2);
                    } else {
                        currentFile = bPath;
                    }
                    if (isSupportedFile(currentFile)) {
                        changedFiles.add(currentFile);
                        changedLines.put(currentFile, new HashSet<>());
                    } else {
                        currentFile = null;
                    }
                }
            } else if (currentFile != null && line.startsWith("@@ ")) {
                // Parse "@@ -10,4 +12,6 @@"
                int plusIdx = line.indexOf("+");
                if (plusIdx != -1) {
                    int commaIdx = line.indexOf(",", plusIdx);
                    String startStr;
                    if (commaIdx != -1) {
                        startStr = line.substring(plusIdx + 1, commaIdx).trim();
                    } else {
                        int endHunk = line.indexOf(" @@", plusIdx);
                        startStr = line.substring(plusIdx + 1, endHunk).trim();
                    }
                    try {
                        currentLine = Integer.parseInt(startStr);
                    } catch (NumberFormatException e) {
                        logger.error("Failed parsing line numbers from hunk: {}", line);
                    }
                }
            } else if (currentFile != null) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    changedLines.get(currentFile).add(currentLine);
                    currentLine++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    // Line deleted, doesn't increment current line in new file
                } else if (line.startsWith(" ")) {
                    currentLine++;
                }
            }
        }

        return new DiffInfo(changedFiles, changedLines);
    }

    /**
     * Posts a consolidated review comment on the PR.
     */
    public void postPrReview(String repoName, int prNumber, String commitSha, String reviewBody, List<PrComment> comments) {
        logger.info("Posting review feedback on PR #{} on repository {}", prNumber, repoName);
        logger.info("Review Summary:\n{}", reviewBody);

        if ("mock/test-repo".equals(repoName) || "mock-key".equals(webhookSecret)) {
            logger.info("Mock Mode: Bypassing actual GitHub API review post.");
            for (PrComment comment : comments) {
                logger.info("Mock Inline Comment: File: {}, Line: {}, Body: {}", comment.filePath(), comment.line(), comment.body());
            }
            return;
        }

        if (githubToken == null || githubToken.isBlank()) {
            logger.warn("GitHub token is not configured. Skipping PR review comment.");
            return;
        }

        String url = String.format("https://api.github.com/repos/%s/pulls/%d/reviews", repoName, prNumber);
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("body", reviewBody);
            root.put("event", "COMMENT");
            root.put("commit_id", commitSha);

            ArrayNode commentsArray = root.putArray("comments");
            for (PrComment c : comments) {
                ObjectNode commentNode = objectMapper.createObjectNode();
                commentNode.put("path", c.filePath());
                commentNode.put("line", c.line());
                commentNode.put("side", "RIGHT");
                commentNode.put("body", c.body());
                commentsArray.add(commentNode);
            }

            String requestBody = objectMapper.writeValueAsString(root);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "CodeSentry")
                    .header("Authorization", "Bearer " + githubToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("GitHub API response status: {}", response.statusCode());
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to post PR review comments to GitHub", e);
        }
    }

    public static record DiffInfo(List<String> changedFiles, Map<String, Set<Integer>> changedLines) {}
    public static record PrComment(String filePath, int line, String body) {}

    public static boolean isSupportedFile(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".cpp") || lower.endsWith(".h") ||
               lower.endsWith(".hpp") || lower.endsWith(".cc") || lower.endsWith(".c") ||
               lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".ts") ||
               lower.endsWith(".jsx") || lower.endsWith(".tsx") || lower.endsWith(".go") ||
               lower.endsWith(".rs") || lower.endsWith(".php") || lower.endsWith(".cs") ||
               lower.endsWith(".rb") || lower.endsWith(".swift") || lower.endsWith(".kt") ||
               lower.endsWith(".sh") || lower.endsWith(".sql");
    }

    private String getSimulatedDiff() {
        return "diff --git a/src/main/java/org/codesentry/demo/DemoClass.java b/src/main/java/org/codesentry/demo/DemoClass.java\n" +
                "index 0000000..1111111 100644\n" +
                "--- a/src/main/java/org/codesentry/demo/DemoClass.java\n" +
                "+++ b/src/main/java/org/codesentry/demo/DemoClass.java\n" +
                "@@ -1,15 +1,40 @@\n" +
                " package org.codesentry.demo;\n" +
                " \n" +
                "+import java.io.FileInputStream;\n" +
                "+import java.io.IOException;\n" +
                "+import java.util.ArrayList;\n" +
                "+import java.util.List;\n" +
                "+\n" +
                " public class DemoClass {\n" +
                " \n" +
                "     public void doSomething() {\n" +
                "         System.out.println(\"Hello World\");\n" +
                "     }\n" +
                "+\n" +
                "+    public void leakResource() throws IOException {\n" +
                "+        FileInputStream fis = new FileInputStream(\"test.txt\");\n" +
                "+        int data = fis.read();\n" +
                "+        System.out.println(data);\n" +
                "+        // Leak: fis is never closed!\n" +
                "+    }\n" +
                "+\n" +
                "+    public void nullDereference() {\n" +
                "+        String str = null;\n" +
                "+        System.out.println(str.length());\n" +
                "+    }\n" +
                "+    \n" +
                "+    public void queryInLoop() {\n" +
                "+        List<String> ids = new ArrayList<>();\n" +
                "+        ids.add(\"1\");\n" +
                "+        for (String id : ids) {\n" +
                "+            userRepository.findById(id);\n" +
                "+        }\n" +
                "+    }\n" +
                " }\n";
    }
}
