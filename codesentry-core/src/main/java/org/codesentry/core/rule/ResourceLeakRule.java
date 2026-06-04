package org.codesentry.core.rule;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.analysis.cfg.CfgNode;
import org.codesentry.core.analysis.cfg.ControlFlowGraph;
import org.codesentry.core.analysis.dfg.DataFlowGraph;
import org.codesentry.core.model.Finding;

import java.util.*;

public class ResourceLeakRule implements Rule {

    private static final Set<String> RESOURCE_KEYWORDS = new HashSet<>(Arrays.asList(
            "stream", "reader", "writer", "connection", "socket", "session",
            "resource", "closeable", "autocloseable", "channel", "zipfile"
    ));

    @Override
    public String getId() {
        return "RULE-001-RESOURCE-LEAK";
    }

    @Override
    public String getName() {
        return "Resource Leak Detection";
    }

    @Override
    public String getDescription() {
        return "Detects AutoCloseables, Streams, and Connections that escape the method block without being closed on all exit paths.";
    }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> entry : context.getParsedAsts().entrySet()) {
            String filePath = entry.getKey();
            com.github.javaparser.ast.CompilationUnit cu = entry.getValue();

            cu.findAll(MethodDeclaration.class).forEach(method -> {
                try {
                    org.codesentry.core.analysis.cfg.CfgBuilder builder = new org.codesentry.core.analysis.cfg.CfgBuilder();
                    ControlFlowGraph cfg = builder.build(method);
                    DataFlowGraph dfg = new DataFlowGraph(cfg, method);

                    for (DataFlowGraph.VariableDefinition def : dfg.getAllDefinitions()) {
                        if (isResource(def) && !isDeclaredInTryWithResources(def.astNode())) {
                            List<List<CfgNode>> leakPaths = new ArrayList<>();
                            Set<CfgNode> visited = new HashSet<>();
                            List<CfgNode> currentPath = new ArrayList<>();

                            // Trace from definition node
                            hasLeakPath(cfg, def.node(), def.varName(), visited, currentPath, leakPaths);

                            if (!leakPaths.isEmpty()) {
                                // We have a leak path! Construct finding
                                List<Finding.DataflowStep> steps = new ArrayList<>();
                                for (CfgNode node : leakPaths.get(0)) {
                                    int line = node.getAstNode() != null ? node.getAstNode().getRange().map(r -> r.begin.line).orElse(-1) : -1;
                                    String desc = node.getLabel() != null ? node.getLabel() : (node.getAstNode() != null ? node.getAstNode().toString() : node.getType().toString());
                                    steps.add(new Finding.DataflowStep(filePath, line, desc));
                                }

                                int defLine = def.astNode().getRange().map(r -> r.begin.line).orElse(-1);
                                String snippet = def.astNode().toString();

                                Finding finding = Finding.builder()
                                        .ruleId(getId())
                                        .severity(Finding.Severity.ERROR)
                                        .file(filePath)
                                        .line(defLine)
                                        .codeSnippet(snippet)
                                        .dataflowPath(steps)
                                        .message(String.format("Resource '%s' of type '%s' is opened but not closed on all exit paths.",
                                                def.varName(), getTypeName(def)))
                                        .build();

                                findings.add(finding);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Log or handle analysis error gracefully to avoid crashing pipeline
                }
            });
        }

        return findings;
    }

    private boolean isResource(DataFlowGraph.VariableDefinition def) {
        String typeName = getTypeName(def).toLowerCase();
        for (String keyword : RESOURCE_KEYWORDS) {
            if (typeName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String getTypeName(DataFlowGraph.VariableDefinition def) {
        if (def.astNode() instanceof VariableDeclarator dec) {
            return dec.getTypeAsString();
        } else if (def.astNode() instanceof AssignExpr assign) {
            // For assignments, try to see if it's a known variable and resolve type, or just assume from variable name
            return "";
        }
        return "";
    }

    private boolean isDeclaredInTryWithResources(Node astNode) {
        if (astNode == null) return false;
        Optional<Node> parent = astNode.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TryStmt tryStmt) {
                if (tryStmt.getResources().stream().anyMatch(res -> res.containsWithinRange(astNode))) {
                    return true;
                }
            }
            parent = parent.get().getParentNode();
        }
        return false;
    }

    private void hasLeakPath(ControlFlowGraph cfg, CfgNode current, String varName,
                             Set<CfgNode> visited, List<CfgNode> currentPath, List<List<CfgNode>> leakPaths) {
        if (visited.contains(current)) {
            return;
        }

        visited.add(current);
        currentPath.add(current);

        // 1. Check if resource is closed in the current node
        if (isClosedAt(current, varName)) {
            visited.remove(current);
            currentPath.remove(currentPath.size() - 1);
            return;
        }

        // 2. Check if resource escapes in the current node
        if (isEscapedAt(current, varName)) {
            visited.remove(current);
            currentPath.remove(currentPath.size() - 1);
            return;
        }

        // 3. Check if we reached the exit node without closing
        if (current.getType() == CfgNode.Type.EXIT) {
            leakPaths.add(new ArrayList<>(currentPath));
            visited.remove(current);
            currentPath.remove(currentPath.size() - 1);
            return;
        }

        // 4. Recurse for successors
        for (CfgNode succ : cfg.getSuccessors(current)) {
            hasLeakPath(cfg, succ, varName, visited, currentPath, leakPaths);
        }

        visited.remove(current);
        currentPath.remove(currentPath.size() - 1);
    }

    private boolean isClosedAt(CfgNode node, String varName) {
        if (node.getAstNode() == null) return false;

        final boolean[] closed = {false};
        node.getAstNode().walk(Node.TreeTraversal.PREORDER, astNode -> {
            if (astNode instanceof MethodCallExpr call) {
                if (call.getNameAsString().equals("close") && call.getScope().isPresent()) {
                    if (call.getScope().get().toString().equals(varName)) {
                        closed[0] = true;
                    }
                }
            }
        });
        return closed[0];
    }

    private boolean isEscapedAt(CfgNode node, String varName) {
        if (node.getAstNode() == null) return false;

        final boolean[] escaped = {false};
        node.getAstNode().walk(Node.TreeTraversal.PREORDER, astNode -> {
            // Escapes via return statement
            if (astNode instanceof ReturnStmt ret) {
                if (ret.getExpression().isPresent() && ret.getExpression().get().toString().contains(varName)) {
                    escaped[0] = true;
                }
            }
            // Escapes by assignment to a field (e.g. this.myField = varName)
            if (astNode instanceof AssignExpr assign) {
                if (assign.getTarget() instanceof FieldAccessExpr ||
                        (assign.getTarget().isNameExpr() && isFieldName(assign.getTarget().asNameExpr().getNameAsString()))) {
                    if (assign.getValue().toString().contains(varName)) {
                        escaped[0] = true;
                    }
                }
            }
        });
        return escaped[0];
    }

    private boolean isFieldName(String name) {
        // A simple heuristic for field assignment in class scope
        return name.startsWith("this.") || name.startsWith("field") || Character.isUpperCase(name.charAt(0));
    }
}
