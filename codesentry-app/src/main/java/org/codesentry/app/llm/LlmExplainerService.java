package org.codesentry.app.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codesentry.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Service
public class LlmExplainerService {

    private static final Logger logger = LoggerFactory.getLogger(LlmExplainerService.class);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.api-url}")
    private String apiUrl;

    @Value("${anthropic.model}")
    private String model;

    public static record LlmEnrichment(String rationale, String fix, int priorityScore) {}

    /**
     * Enriches a single static analysis finding by calling the Anthropic API.
     */
    public LlmEnrichment enrichFinding(Finding finding) {
        if ("mock-key".equals(apiKey) || apiKey == null || apiKey.isEmpty()) {
            logger.info("Mock Mode: Generating simulated LLM explanation for finding of rule: {}", finding.getRuleId());
            return getSimulatedEnrichment(finding);
        }

        String lang = getLanguageFromPath(finding.getFile());
        String systemPrompt = "You are a senior software quality and static analysis engineer. " +
                "You will receive a static analysis finding. You must respond ONLY with a single JSON object. " +
                "Do NOT wrap it in markdown block, do NOT write any prefaces or postfaces. " +
                "The JSON must have exactly the following structure:\n" +
                "{\n" +
                "  \"rationale\": \"A detailed explanation of why this is a bug, its runtime impact, and how to fix it.\",\n" +
                "  \"fix\": \"The concrete corrected " + lang + " code snippet for this specific issue.\",\n" +
                "  \"priorityScore\": 8\n" +
                "}\n" +
                "priorityScore must be an integer between 1 and 10, where 10 is critical.";

        String userPrompt = String.format("Language: %s\nRule ID: %s\nSeverity: %s\nFile: %s\nLine: %d\nCode Snippet:\n%s\nMessage: %s",
                lang, finding.getRuleId(), finding.getSeverity(), finding.getFile(), finding.getLine(),
                finding.getCodeSnippet(), finding.getMessage());

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", 1024);
            root.put("system", systemPrompt);

            ArrayNodeMessages messages = new ArrayNodeMessages(root.putArray("messages"));
            messages.addMessage("user", userPrompt);

            String requestBody = objectMapper.writeValueAsString(root);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("User-Agent", "CodeSentry")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        JsonNode responseJson = objectMapper.readTree(response.body());
                        String text = responseJson.path("content").path(0).path("text").asText();
                        return parseAndGuardResponse(text);
                    } else if (response.statusCode() >= 500) {
                        logger.warn("Anthropic API returned 5xx error: {}. Attempt {}/{}", response.statusCode(), attempt, maxRetries);
                        if (attempt == maxRetries) {
                            return getSimulatedEnrichment(finding);
                        }
                        Thread.sleep((long) Math.pow(2, attempt) * 1000L);
                    } else {
                        logger.error("Anthropic API failed with status code: {}, body: {}", response.statusCode(), response.body());
                        return getSimulatedEnrichment(finding);
                    }
                } catch (java.net.http.HttpTimeoutException e) {
                    logger.warn("Anthropic API request timed out. Attempt {}/{}", attempt, maxRetries);
                    if (attempt == maxRetries) {
                        return getSimulatedEnrichment(finding);
                    }
                    Thread.sleep((long) Math.pow(2, attempt) * 1000L);
                }
            }
            return getSimulatedEnrichment(finding);
        } catch (Exception e) {
            logger.error("Error invoking Anthropic API", e);
            return getSimulatedEnrichment(finding);
        }
    }

    private LlmEnrichment parseAndGuardResponse(String rawText) {
        String cleanText = rawText.trim();

        if (cleanText.startsWith("```")) {
            int firstNewline = cleanText.indexOf("\n");
            int lastBackticks = cleanText.lastIndexOf("```");
            if (firstNewline != -1 && lastBackticks != -1 && lastBackticks > firstNewline) {
                cleanText = cleanText.substring(firstNewline + 1, lastBackticks).trim();
            }
        }

        try {
            JsonNode root = objectMapper.readTree(cleanText);
            String rationale = root.path("rationale").asText();
            String fix = root.path("fix").asText();
            int priorityScore = root.path("priorityScore").asInt(5);
            return new LlmEnrichment(rationale, fix, priorityScore);
        } catch (Exception e) {
            logger.warn("Failed to parse LLM response as JSON. Raw response: {}", rawText, e);
            return new LlmEnrichment("Analysis detected code violating safety policies. Review this location to avoid crashes.", "// Code review fix check", 6);
        }
    }

    private String getLanguageFromPath(String path) {
        if (path == null) return "Java";
        String lower = path.toLowerCase();
        if (lower.endsWith(".cpp") || lower.endsWith(".h") || lower.endsWith(".hpp") || lower.endsWith(".cc") || lower.endsWith(".c")) {
            return "C++";
        }
        if (lower.endsWith(".py")) {
            return "Python";
        }
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) {
            return "JavaScript";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "TypeScript";
        }
        if (lower.endsWith(".go")) {
            return "Go";
        }
        if (lower.endsWith(".rs")) {
            return "Rust";
        }
        return "Java";
    }

    private LlmEnrichment getSimulatedEnrichment(Finding finding) {
        String lang = getLanguageFromPath(finding.getFile());
        String ruleId = finding.getRuleId();

        if ("RULE-001-RESOURCE-LEAK".equals(ruleId)) {
            if ("C++".equals(lang)) {
                return new LlmEnrichment(
                        "The resource is opened but never closed inside the function. " +
                                "This can deplete file descriptors or cause leaks, eventually crashing the application under load.",
                        "void process() {\n" +
                                "    FILE* file = fopen(\"data.txt\", \"r\");\n" +
                                "    if (!file) return;\n" +
                                "    std::cout << \"Reading file...\" << std::endl;\n" +
                                "    fclose(file);\n" +
                                "}",
                        9
                );
            } else if ("Python".equals(lang)) {
                return new LlmEnrichment(
                        "The file resource is opened but never closed. Use Python's 'with' context manager for safe automatic cleanup.",
                        "with open('data.txt', 'r') as file:\n" +
                                "    print(file.read())",
                        9
                );
            } else {
                return new LlmEnrichment(
                        "The AutoCloseable resource is instantiated but never closed inside the method. " +
                                "This can deplete file descriptors, connection pools, or system resources, eventually crashing the application under load.",
                        "try (FileInputStream fis = new FileInputStream(\"test.txt\")) {\n" +
                                "    int data = fis.read();\n" +
                                "    System.out.println(data);\n" +
                                "}",
                        9
                );
            }
        } else if ("RULE-002-NULL-SAFETY".equals(ruleId)) {
            if ("C++".equals(lang)) {
                return new LlmEnrichment(
                        "The pointer is assigned to nullptr/NULL and dereferenced subsequently without a check.",
                        "Type* ptr = nullptr;\n" +
                                "if (ptr != nullptr) {\n" +
                                "    ptr->doSomething();\n" +
                                "}",
                        8
                );
            } else if ("Python".equals(lang)) {
                return new LlmEnrichment(
                        "The variable is None and accessed without verification, causing an AttributeError.",
                        "val = None\n" +
                                "if val is not None:\n" +
                                "    val.do_something()",
                        8
                );
            } else if ("JavaScript".equals(lang) || "TypeScript".equals(lang)) {
                return new LlmEnrichment(
                        "Property access on a null/undefined value causes TypeError. Use optional chaining or guard check.",
                        "let obj = null;\n" +
                                "console.log(obj?.property);",
                        8
                );
            } else {
                return new LlmEnrichment(
                        "The variable is initialized to null and dereferenced subsequently without a protective null-check, " +
                                "which will consistently trigger a NullPointerException at runtime on this execution path.",
                        "String str = null;\n" +
                                "if (str != null) {\n" +
                                "    System.out.println(str.length());\n" +
                                "}",
                        8
                );
            }
        } else if ("RULE-003-CONCURRENCY".equals(ruleId)) {
            if ("C++".equals(lang)) {
                return new LlmEnrichment(
                        "Shared mutable fields accessed without lock guard.",
                        "std::lock_guard<std::mutex> lock(mtx);\n" +
                                "sharedField = newValue;",
                        7
                );
            } else {
                return new LlmEnrichment(
                        "Shared mutable fields accessed without synchronized blocks or lock APIs in singleton scoped components or thread bodies present concurrency hazard races.",
                        "synchronized(lock) {\n" +
                                "    sharedField = newValue;\n" +
                                "}",
                        7
                );
            }
        } else {
            // N+1 Query
            if ("JavaScript".equals(lang) || "TypeScript".equals(lang)) {
                return new LlmEnrichment(
                        "Asynchronous network/database operations executed inside a loop cause multiple roundtrips. " +
                                "Use Promise.all() to run queries in parallel, or batch them.",
                        "const ids = [...];\n" +
                                "const users = await db.findAllByIds(ids);",
                        6
                );
            } else {
                return new LlmEnrichment(
                        "N+1 database query operations in collections/loops cause multiple database roundtrips, slowing down performance. " +
                                "Use batch queries or JOIN fetch operations to solve this issue.",
                        "List<String> ids = ...;\n" +
                                "List<User> users = userRepository.findAllById(ids);",
                        6
                );
            }
        }
    }

    private static class ArrayNodeMessages {
        private final com.fasterxml.jackson.databind.node.ArrayNode arrayNode;

        ArrayNodeMessages(com.fasterxml.jackson.databind.node.ArrayNode arrayNode) {
            this.arrayNode = arrayNode;
        }

        void addMessage(String role, String content) {
            ObjectNode msg = arrayNode.addObject();
            msg.put("role", role);
            msg.put("content", content);
        }
    }
}
