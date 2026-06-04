package org.codesentry.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.codesentry.app.service.AnalysisPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private final AnalysisPipeline analysisPipeline;

    @Value("${github.webhook-secret}")
    private String webhookSecret;

    public WebhookController(AnalysisPipeline analysisPipeline) {
        this.analysisPipeline = analysisPipeline;
    }

    @PostMapping("/github")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestHeader(value = "X-GitHub-Event", required = false) String gitHubEvent) {

        logger.info("Received GitHub webhook event: {}", gitHubEvent);

        if (signatureHeader == null || !verifySignature(payload, signatureHeader)) {
            logger.warn("Invalid webhook signature header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        if (!"pull_request".equals(gitHubEvent)) {
            return ResponseEntity.ok("Event ignored (only pull_request events processed)");
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = root.path("action").asText();
            if ("opened".equals(action) || "synchronize".equals(action) || "reopened".equals(action)) {
                int prNumber = root.path("number").asInt();
                String repoName = root.path("repository").path("full_name").asText();
                String commitSha = root.path("pull_request").path("head").path("sha").asText();

                logger.info("Triggering analysis pipeline for PR #{} on repository {} at commit {}", prNumber, repoName, commitSha);

                // Run analysis asynchronously to respond immediately to GitHub
                executorService.submit(() -> {
                    try {
                        analysisPipeline.runPipeline(repoName, prNumber, commitSha);
                    } catch (Exception e) {
                        logger.error("Error executing analysis pipeline for PR #{}", prNumber, e);
                    }
                });

                return ResponseEntity.ok("Analysis pipeline triggered");
            } else {
                return ResponseEntity.ok("PR action '" + action + "' ignored");
            }
        } catch (Exception e) {
            logger.error("Failed to parse webhook payload", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid JSON");
        }
    }

    private boolean verifySignature(String payload, String signatureHeader) {
        if (!signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String signature = signatureHeader.substring(7);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedSignature = HexFormat.of().formatHex(hmacBytes);
            return MessageDigest.isEqual(computedSignature.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.error("Failed verifying signature", e);
            return false;
        }
    }
}
