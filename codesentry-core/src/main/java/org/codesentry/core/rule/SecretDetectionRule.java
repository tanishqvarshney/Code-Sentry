package org.codesentry.core.rule;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.model.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class SecretDetectionRule implements Rule {

    private static final Pattern SECRET_NAME_PATTERN = Pattern.compile("(?i)(password|secret|token|api_?key|credentials|auth)");
    private static final Pattern DUMMY_SECRET_PATTERN = Pattern.compile("(?i)(^$|dummy|test|example|mock|fake|null|empty)");

    @Override
    public String getId() {
        return "RULE-006-HARDCODED-SECRET";
    }

    @Override
    public String getName() {
        return "Hardcoded Secret Detection";
    }

    @Override
    public String getDescription() {
        return "Detects hardcoded passwords, tokens, and API keys assigned as string literals in the code.";
    }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> entry : context.getParsedAsts().entrySet()) {
            String filePath = entry.getKey();
            com.github.javaparser.ast.CompilationUnit cu = entry.getValue();

            // Check field declarations (e.g., private static final String DB_PASSWORD = "...")
            cu.findAll(FieldDeclaration.class).forEach(field -> {
                field.getVariables().forEach(var -> {
                    String name = var.getNameAsString();
                    if (isSecretName(name) && var.getInitializer().isPresent() && var.getInitializer().get() instanceof StringLiteralExpr) {
                        String value = var.getInitializer().get().asStringLiteralExpr().getValue();
                        if (!isDummySecret(value)) {
                            int line = var.getRange().map(r -> r.begin.line).orElse(-1);
                            addFinding(findings, filePath, line, field.toString(), name);
                        }
                    }
                });
            });

            // Check variable assignments (e.g., String token = "...") and AssignExpr
            cu.walk(Node.TreeTraversal.PREORDER, node -> {
                if (node instanceof VariableDeclarator var) {
                    // Skip if parent is FieldDeclaration, already handled
                    if (var.getParentNode().isPresent() && var.getParentNode().get() instanceof FieldDeclaration) {
                        return;
                    }
                    String name = var.getNameAsString();
                    if (isSecretName(name) && var.getInitializer().isPresent() && var.getInitializer().get() instanceof StringLiteralExpr) {
                        String value = var.getInitializer().get().asStringLiteralExpr().getValue();
                        if (!isDummySecret(value)) {
                            int line = var.getRange().map(r -> r.begin.line).orElse(-1);
                            addFinding(findings, filePath, line, var.toString(), name);
                        }
                    }
                } else if (node instanceof AssignExpr assign) {
                    if (assign.getTarget().isNameExpr()) {
                        String name = assign.getTarget().asNameExpr().getNameAsString();
                        if (isSecretName(name) && assign.getValue() instanceof StringLiteralExpr) {
                            String value = assign.getValue().asStringLiteralExpr().getValue();
                            if (!isDummySecret(value)) {
                                int line = assign.getRange().map(r -> r.begin.line).orElse(-1);
                                addFinding(findings, filePath, line, assign.toString(), name);
                            }
                        }
                    }
                }
            });
        }

        return findings;
    }

    private boolean isSecretName(String name) {
        return SECRET_NAME_PATTERN.matcher(name).find();
    }

    private boolean isDummySecret(String value) {
        return value.length() < 3 || DUMMY_SECRET_PATTERN.matcher(value).find();
    }

    private void addFinding(List<Finding> findings, String filePath, int line, String snippet, String varName) {
        findings.add(Finding.builder()
                .ruleId(getId())
                .severity(Finding.Severity.ERROR)
                .file(filePath)
                .line(line)
                .codeSnippet(snippet.trim())
                .message(String.format("Hardcoded secret detected: Variable '%s' is assigned a string literal. Use a secure vault or environment variables.", varName))
                .build());
    }
}
