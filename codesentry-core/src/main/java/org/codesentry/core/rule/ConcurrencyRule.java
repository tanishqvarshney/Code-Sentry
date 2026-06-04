package org.codesentry.core.rule;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.model.Finding;

import java.util.*;

public class ConcurrencyRule implements Rule {

    @Override
    public String getId() {
        return "RULE-003-CONCURRENCY";
    }

    @Override
    public String getName() {
        return "Concurrency Hazards";
    }

    @Override
    public String getDescription() {
        return "Flags thread-safety hazards such as unguarded shared mutable fields, non-atomic check-then-act operations, and unsafe publication.";
    }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> entry : context.getParsedAsts().entrySet()) {
            String filePath = entry.getKey();
            com.github.javaparser.ast.CompilationUnit cu = entry.getValue();

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                boolean isConcurrentContext = isConcurrentScope(clazz);

                // Collect mutable field names
                Map<String, FieldDeclaration> mutableFields = new HashMap<>();
                clazz.getFields().forEach(field -> {
                    if (!field.isFinal() && !isThreadSafeType(field)) {
                        for (VariableDeclarator var : field.getVariables()) {
                            mutableFields.put(var.getNameAsString(), field);
                        }
                    }
                });

                // Analyze methods
                clazz.getMethods().forEach(method -> {
                    // Check 1: Shared mutable state accessed without synchronization
                    if (isConcurrentContext && !method.isSynchronized() && !hasLockInMethod(method)) {
                        Set<String> reportedFields = new HashSet<>();
                        method.walk(Node.TreeTraversal.PREORDER, node -> {
                            if (node instanceof NameExpr nameExpr) {
                                String name = nameExpr.getNameAsString();
                                if (mutableFields.containsKey(name) && !reportedFields.contains(name) && isWrittenOrReadMutable(nameExpr, node)) {
                                    if (!isEnclosedInSynchronized(nameExpr)) {
                                        reportedFields.add(name);
                                        int line = nameExpr.getRange().map(r -> r.begin.line).orElse(-1);
                                        Finding f = Finding.builder()
                                                .ruleId(getId())
                                                .severity(Finding.Severity.WARNING)
                                                .file(filePath)
                                                .line(line)
                                                .codeSnippet(nameExpr.getParentNode().map(Node::toString).orElse(nameExpr.toString()))
                                                .message(String.format("Shared mutable field '%s' is accessed without synchronization in concurrent context.", name))
                                                .build();
                                        findings.add(f);
                                    }
                                }
                            }
                        });
                    }

                    // Check 2: Non-atomic check-then-act
                    method.findAll(IfStmt.class).forEach(ifStmt -> {
                        if (isCheckThenActOnCollection(ifStmt)) {
                            int line = ifStmt.getRange().map(r -> r.begin.line).orElse(-1);
                            Finding f = Finding.builder()
                                    .ruleId(getId())
                                    .severity(Finding.Severity.WARNING)
                                    .file(filePath)
                                    .line(line)
                                    .codeSnippet(ifStmt.getCondition().toString())
                                    .message("Non-atomic check-then-act operation detected on collection. Use computeIfAbsent or putIfAbsent.")
                                    .build();
                            findings.add(f);
                        }
                    });

                    // Check 3: Unsafe publication
                    method.findAll(ReturnStmt.class).forEach(ret -> {
                        if (ret.getExpression().isPresent() && ret.getExpression().get().isNameExpr()) {
                            String returnedVar = ret.getExpression().get().asNameExpr().getNameAsString();
                            if (mutableFields.containsKey(returnedVar) && isCollectionType(mutableFields.get(returnedVar))) {
                                int line = ret.getRange().map(r -> r.begin.line).orElse(-1);
                                Finding f = Finding.builder()
                                        .ruleId(getId())
                                        .severity(Finding.Severity.WARNING)
                                        .file(filePath)
                                        .line(line)
                                        .codeSnippet(ret.toString())
                                        .message(String.format("Unsafe publication: private mutable collection field '%s' is returned directly.", returnedVar))
                                        .build();
                                findings.add(f);
                            }
                        }
                    });
                });
            });
        }

        return findings;
    }

    private boolean isConcurrentScope(ClassOrInterfaceDeclaration clazz) {
        // Classes with concurrent annotations, Spring singletons, or Runnable implementations
        boolean isSpringComponent = clazz.getAnnotations().stream().anyMatch(ann -> {
            String name = ann.getNameAsString();
            return name.equals("RestController") || name.endsWith(".RestController") ||
                    name.equals("Controller") || name.endsWith(".Controller") ||
                    name.equals("Service") || name.endsWith(".Service") ||
                    name.equals("Component") || name.endsWith(".Component") ||
                    name.equals("Repository") || name.endsWith(".Repository");
        });
        boolean implementsRunnable = clazz.getImplementedTypes().stream().anyMatch(t -> {
            String name = t.getNameAsString();
            return name.equals("Runnable") || name.equals("Callable");
        });
        return isSpringComponent || implementsRunnable;
    }

    private boolean isThreadSafeType(FieldDeclaration field) {
        String typeStr = field.getElementType().asString();
        return typeStr.contains("Atomic") || typeStr.contains("Concurrent") ||
                typeStr.contains("Lock") || typeStr.contains("ReentrantLock") ||
                typeStr.contains("Semaphore") || typeStr.contains("CountDownLatch");
    }

    private boolean isCollectionType(FieldDeclaration field) {
        String typeStr = field.getElementType().asString();
        return typeStr.startsWith("List") || typeStr.startsWith("Map") ||
                typeStr.startsWith("Set") || typeStr.startsWith("Collection") ||
                typeStr.startsWith("ArrayList") || typeStr.startsWith("HashMap") ||
                typeStr.startsWith("HashSet");
    }

    private boolean hasLockInMethod(MethodDeclaration method) {
        final boolean[] hasLock = {false};
        method.walk(Node.TreeTraversal.PREORDER, node -> {
            if (node instanceof MethodCallExpr call) {
                String name = call.getNameAsString();
                if (name.equals("lock") || name.equals("lockInterruptibly") || name.equals("tryLock")) {
                    hasLock[0] = true;
                }
            }
        });
        return hasLock[0];
    }

    private boolean isWrittenOrReadMutable(NameExpr nameExpr, Node root) {
        return true;
    }

    private boolean isEnclosedInSynchronized(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof SynchronizedStmt) {
                return true;
            }
            parent = parent.get().getParentNode();
        }
        return false;
    }

    private boolean isCheckThenActOnCollection(IfStmt ifStmt) {
        // e.g. if (!map.containsKey(k)) { map.put(k, v); }
        Node cond = ifStmt.getCondition();
        if (cond.toString().contains("containsKey") || cond.toString().contains("get") || cond.toString().contains("== null")) {
            final boolean[] hasPutOrAdd = {false};
            ifStmt.getThenStmt().walk(Node.TreeTraversal.PREORDER, child -> {
                if (child instanceof MethodCallExpr call) {
                    String name = call.getNameAsString();
                    if (name.equals("put") || name.equals("add") || name.equals("set") || name.equals("remove")) {
                        hasPutOrAdd[0] = true;
                    }
                }
            });
            return hasPutOrAdd[0];
        }
        return false;
    }
}
