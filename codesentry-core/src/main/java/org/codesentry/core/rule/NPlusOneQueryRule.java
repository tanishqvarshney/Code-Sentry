package org.codesentry.core.rule;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.*;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.model.Finding;

import java.util.*;

public class NPlusOneQueryRule implements Rule {

    @Override
    public String getId() {
        return "RULE-004-NPLUSONE-QUERY";
    }

    @Override
    public String getName() {
        return "N+1 Database Query Detection";
    }

    @Override
    public String getDescription() {
        return "Detects repository/database query operations executed inside loops or stream iterations, causing performance bottlenecks.";
    }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> entry : context.getParsedAsts().entrySet()) {
            String filePath = entry.getKey();
            com.github.javaparser.ast.CompilationUnit cu = entry.getValue();

            cu.findAll(MethodDeclaration.class).forEach(method -> {
                method.walk(Node.TreeTraversal.PREORDER, node -> {
                    if (isLoopStatement(node)) {
                        // Scan for DB calls in loops
                        node.walk(Node.TreeTraversal.PREORDER, child -> {
                            if (child instanceof MethodCallExpr call) {
                                if (isDbQueryCall(call)) {
                                    int line = call.getRange().map(r -> r.begin.line).orElse(-1);
                                    Finding f = Finding.builder()
                                            .ruleId(getId())
                                            .severity(Finding.Severity.WARNING)
                                            .file(filePath)
                                            .line(line)
                                            .codeSnippet(call.toString())
                                            .message("N+1 Query Hazard: Database query operation is executed inside a loop.")
                                            .build();
                                    findings.add(f);
                                }
                            }
                        });
                    } else if (node instanceof MethodCallExpr streamCall) {
                        // Scan for stream operations like .map(x -> repo.find(x)) or .forEach(x -> repo.save(x))
                        String name = streamCall.getNameAsString();
                        if (name.equals("map") || name.equals("forEach") || name.equals("flatMap") || name.equals("filter")) {
                            streamCall.getArguments().forEach(arg -> {
                                if (arg instanceof LambdaExpr lambda) {
                                    lambda.getBody().walk(Node.TreeTraversal.PREORDER, child -> {
                                        if (child instanceof MethodCallExpr call) {
                                            if (isDbQueryCall(call)) {
                                                int line = call.getRange().map(r -> r.begin.line).orElse(-1);
                                                Finding f = Finding.builder()
                                                        .ruleId(getId())
                                                        .severity(Finding.Severity.WARNING)
                                                        .file(filePath)
                                                        .line(line)
                                                        .codeSnippet(call.toString())
                                                        .message("N+1 Query Hazard: Database query operation is executed inside a stream iteration.")
                                                        .build();
                                                findings.add(f);
                                            }
                                        }
                                    });
                                }
                            });
                        }
                    }
                });
            });
        }

        return findings;
    }

    private boolean isLoopStatement(Node node) {
        return node instanceof ForStmt || node instanceof ForEachStmt ||
                node instanceof WhileStmt || node instanceof DoStmt;
    }

    private boolean isDbQueryCall(MethodCallExpr call) {
        // Match target scope name: e.g. userRepository, userDao, clientMapper, entityManager
        String scopeStr = call.getScope().map(Node::toString).orElse("").toLowerCase();
        boolean matchesScope = scopeStr.contains("repository") || scopeStr.contains("dao") ||
                scopeStr.contains("mapper") || scopeStr.contains("manager") ||
                scopeStr.contains("db") || scopeStr.equals("em");

        // Match method name: findById, getOne, save, delete, update, select
        String methodName = call.getNameAsString().toLowerCase();
        boolean matchesMethod = methodName.startsWith("find") || methodName.startsWith("get") ||
                methodName.startsWith("save") || methodName.startsWith("delete") ||
                methodName.startsWith("update") || methodName.startsWith("select") ||
                methodName.startsWith("query") || methodName.startsWith("insert");

        return matchesScope && matchesMethod;
    }
}
