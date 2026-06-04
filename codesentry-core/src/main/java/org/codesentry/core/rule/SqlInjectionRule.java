package org.codesentry.core.rule;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.model.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SqlInjectionRule implements Rule {

    @Override
    public String getId() {
        return "RULE-005-SQL-INJECTION";
    }

    @Override
    public String getName() {
        return "SQL Injection Detection";
    }

    @Override
    public String getDescription() {
        return "Detects database queries constructed via string concatenation, which are vulnerable to SQL injection attacks.";
    }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> entry : context.getParsedAsts().entrySet()) {
            String filePath = entry.getKey();
            com.github.javaparser.ast.CompilationUnit cu = entry.getValue();

            cu.findAll(MethodDeclaration.class).forEach(method -> {
                method.walk(Node.TreeTraversal.PREORDER, node -> {
                    if (node instanceof MethodCallExpr call) {
                        String methodName = call.getNameAsString();
                        if (isDbQueryMethod(methodName)) {
                            // Check if any argument is a binary expression (string concatenation)
                            call.getArguments().forEach(arg -> {
                                if (arg instanceof BinaryExpr binaryExpr) {
                                    if (binaryExpr.getOperator() == BinaryExpr.Operator.PLUS) {
                                        int line = call.getRange().map(r -> r.begin.line).orElse(-1);
                                        Finding f = Finding.builder()
                                                .ruleId(getId())
                                                .severity(Finding.Severity.ERROR)
                                                .file(filePath)
                                                .line(line)
                                                .codeSnippet(call.toString())
                                                .message("Potential SQL Injection: Database query is constructed using string concatenation. Use parameterized queries instead.")
                                                .build();
                                        findings.add(f);
                                    }
                                }
                            });
                        }
                    }
                });
            });
        }

        return findings;
    }

    private boolean isDbQueryMethod(String methodName) {
        return methodName.equals("executeQuery") || 
               methodName.equals("execute") || 
               methodName.equals("executeUpdate") || 
               methodName.equals("prepareStatement") || 
               methodName.equals("query");
    }
}
