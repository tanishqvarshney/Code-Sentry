package org.codesentry.core.rule;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import org.codesentry.core.analysis.AnalysisContext;
import org.codesentry.core.analysis.cfg.CfgEdge;
import org.codesentry.core.analysis.cfg.CfgNode;
import org.codesentry.core.analysis.cfg.ControlFlowGraph;
import org.codesentry.core.analysis.dfg.DataFlowGraph;
import org.codesentry.core.model.Finding;

import java.util.*;

public class NullSafetyRule implements Rule {

    @Override
    public String getId() {
        return "RULE-002-NULL-SAFETY";
    }

    @Override
    public String getName() {
        return "Null Safety Dereference";
    }

    @Override
    public String getDescription() {
        return "Flags potential NullPointerExceptions by identifying dereferences of variables that can be null without a preceding guard.";
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

                    for (CfgNode node : cfg.getNodes()) {
                        if (node.getAstNode() == null) continue;

                        // Find all dereferences of variables at this node
                        Set<String> dereferencedVars = getDereferencedVariables(node);
                        for (String varName : dereferencedVars) {
                            Set<DataFlowGraph.VariableDefinition> reachingDefs = dfg.getReachingDefinitionsAt(node, varName);

                            for (DataFlowGraph.VariableDefinition def : reachingDefs) {
                                if (isNullDefinition(def)) {
                                    // Check if there is an unguarded path from definition to dereference
                                    Set<CfgNode> visited = new HashSet<>();
                                    List<CfgNode> currentPath = new ArrayList<>();
                                    List<List<CfgNode>> unguardedPaths = new ArrayList<>();

                                    findUnguardedPaths(cfg, def.node(), node, varName, visited, currentPath, unguardedPaths);

                                    if (!unguardedPaths.isEmpty()) {
                                        List<Finding.DataflowStep> steps = new ArrayList<>();
                                        for (CfgNode pNode : unguardedPaths.get(0)) {
                                            int line = pNode.getAstNode() != null ? pNode.getAstNode().getRange().map(r -> r.begin.line).orElse(-1) : -1;
                                            String desc = pNode.getLabel() != null ? pNode.getLabel() : (pNode.getAstNode() != null ? pNode.getAstNode().toString() : pNode.getType().toString());
                                            steps.add(new Finding.DataflowStep(filePath, line, desc));
                                        }

                                        int derefLine = node.getAstNode().getRange().map(r -> r.begin.line).orElse(-1);
                                        String snippet = node.getAstNode().toString();

                                        Finding finding = Finding.builder()
                                                .ruleId(getId())
                                                .severity(Finding.Severity.ERROR)
                                                .file(filePath)
                                                .line(derefLine)
                                                .codeSnippet(snippet)
                                                .dataflowPath(steps)
                                                .message(String.format("Variable '%s' is dereferenced but it can be null (assigned null at line %d).",
                                                        varName, def.astNode().getRange().map(r -> r.begin.line).orElse(-1)))
                                                .build();

                                        findings.add(finding);
                                        break; // Only flag once per dereference node
                                    }
                                }
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

    private boolean isNullDefinition(DataFlowGraph.VariableDefinition def) {
        if (def.astNode() instanceof VariableDeclarator dec) {
            return dec.getInitializer().isPresent() && dec.getInitializer().get() instanceof NullLiteralExpr;
        } else if (def.astNode() instanceof AssignExpr assign) {
            return assign.getValue() instanceof NullLiteralExpr;
        }
        return false;
    }

    private Set<String> getDereferencedVariables(CfgNode node) {
        Set<String> dereferences = new HashSet<>();
        if (node.getAstNode() == null) return dereferences;

        node.getAstNode().walk(Node.TreeTraversal.PREORDER, astNode -> {
            if (astNode instanceof MethodCallExpr call) {
                if (call.getScope().isPresent() && call.getScope().get().isNameExpr()) {
                    dereferences.add(call.getScope().get().asNameExpr().getNameAsString());
                }
            } else if (astNode instanceof FieldAccessExpr fieldAccess) {
                if (fieldAccess.getScope().isNameExpr()) {
                    dereferences.add(fieldAccess.getScope().asNameExpr().getNameAsString());
                }
            }
        });
        return dereferences;
    }

    private void findUnguardedPaths(ControlFlowGraph cfg, CfgNode current, CfgNode target, String varName,
                                    Set<CfgNode> visited, List<CfgNode> currentPath, List<List<CfgNode>> unguardedPaths) {
        if (current.equals(target)) {
            currentPath.add(current);
            unguardedPaths.add(new ArrayList<>(currentPath));
            currentPath.remove(currentPath.size() - 1);
            return;
        }

        if (visited.contains(current)) {
            return;
        }

        visited.add(current);
        currentPath.add(current);

        for (CfgEdge edge : cfg.getOutgoingEdges(current)) {
            CfgNode succ = edge.getTarget();

            // Check if the current edge is a guard that prevents null flow
            if (isGuardingEdge(current, edge, varName)) {
                continue; // Guarded path, stop search on this branch
            }

            findUnguardedPaths(cfg, succ, target, varName, visited, currentPath, unguardedPaths);
        }

        visited.remove(current);
        currentPath.remove(currentPath.size() - 1);
    }

    private boolean isGuardingEdge(CfgNode node, CfgEdge edge, String varName) {
        if (node.getType() != CfgNode.Type.CONDITION || node.getAstNode() == null) {
            return false;
        }

        Node cond = node.getAstNode();
        if (cond instanceof BinaryExpr binaryExpr) {
            boolean isEquals = binaryExpr.getOperator() == BinaryExpr.Operator.EQUALS;
            boolean isNotEquals = binaryExpr.getOperator() == BinaryExpr.Operator.NOT_EQUALS;

            if (isEquals || isNotEquals) {
                boolean hasVar = false;
                boolean hasNull = false;

                if (binaryExpr.getLeft().isNameExpr() && binaryExpr.getLeft().asNameExpr().getNameAsString().equals(varName)) {
                    hasVar = true;
                } else if (binaryExpr.getLeft() instanceof NullLiteralExpr) {
                    hasNull = true;
                }

                if (binaryExpr.getRight().isNameExpr() && binaryExpr.getRight().asNameExpr().getNameAsString().equals(varName)) {
                    hasVar = true;
                } else if (binaryExpr.getRight() instanceof NullLiteralExpr) {
                    hasNull = true;
                }

                if (hasVar && hasNull) {
                    // Try to find the enclosing IfStmt
                    Optional<Node> parent = cond.getParentNode();
                    if (parent.isPresent() && parent.get() instanceof com.github.javaparser.ast.stmt.IfStmt ifStmt) {
                        // For != null, the then branch is guarded (safe)
                        if (isNotEquals) {
                            if (isNodeInside(edge.getTarget(), ifStmt.getThenStmt())) {
                                return true;
                            }
                        }
                        // For == null, any branch that is NOT the then branch is guarded (safe)
                        if (isEquals) {
                            if (!isNodeInside(edge.getTarget(), ifStmt.getThenStmt())) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isNodeInside(CfgNode cfgNode, Node astRoot) {
        if (cfgNode.getAstNode() == null) return false;
        Node current = cfgNode.getAstNode();
        while (current != null) {
            if (current.equals(astRoot)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
