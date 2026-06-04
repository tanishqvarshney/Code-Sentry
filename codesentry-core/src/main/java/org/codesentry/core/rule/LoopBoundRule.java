package org.codesentry.core.rule;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.model.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LoopBoundRule implements Rule {

    @Override
    public String getId() {
        return "RULE-007-LOOP-BOUND";
    }

    @Override
    public String getName() {
        return "Off-By-One Loop Bound Detection";
    }

    @Override
    public String getDescription() {
        return "Detects loop conditions that use <= with size() or length, potentially causing IndexOutOfBoundsException.";
    }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> entry : context.getParsedAsts().entrySet()) {
            String filePath = entry.getKey();
            com.github.javaparser.ast.CompilationUnit cu = entry.getValue();

            cu.findAll(MethodDeclaration.class).forEach(method -> {
                method.walk(Node.TreeTraversal.PREORDER, node -> {
                    if (node instanceof ForStmt forStmt) {
                        if (forStmt.getCompare().isPresent() && forStmt.getCompare().get() instanceof BinaryExpr binaryExpr) {
                            if (binaryExpr.getOperator() == BinaryExpr.Operator.LESS_EQUALS) {
                                // Check if the right side is a method call to size() or length()
                                if (binaryExpr.getRight() instanceof MethodCallExpr call) {
                                    String name = call.getNameAsString();
                                    if (name.equals("size") || name.equals("length")) {
                                        int line = forStmt.getRange().map(r -> r.begin.line).orElse(-1);
                                        Finding f = Finding.builder()
                                                .ruleId(getId())
                                                .severity(Finding.Severity.WARNING)
                                                .file(filePath)
                                                .line(line)
                                                .codeSnippet(binaryExpr.toString())
                                                .message("Potential off-by-one error: Loop condition uses '<=' with size() or length(). Consider using '<'.")
                                                .build();
                                        findings.add(f);
                                    }
                                }
                            }
                        }
                    }
                });
            });
        }

        return findings;
    }
}
