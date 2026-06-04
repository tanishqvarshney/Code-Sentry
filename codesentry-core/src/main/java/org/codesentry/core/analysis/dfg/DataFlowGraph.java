package org.codesentry.core.analysis.dfg;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.codesentry.core.analysis.cfg.CfgNode;
import org.codesentry.core.analysis.cfg.ControlFlowGraph;

import java.util.*;

public class DataFlowGraph {
    private final ControlFlowGraph cfg;
    private final MethodDeclaration method;
    private final List<VariableDefinition> allDefinitions = new ArrayList<>();
    private final Map<CfgNode, Set<VariableDefinition>> gen = new HashMap<>();
    private final Map<CfgNode, Set<VariableDefinition>> kill = new HashMap<>();
    private final Map<CfgNode, Set<VariableDefinition>> in = new HashMap<>();
    private final Map<CfgNode, Set<VariableDefinition>> out = new HashMap<>();

    public static class VariableDefinition {
        private final String varName;
        private final CfgNode node;
        private final Node astNode;

        public VariableDefinition(String varName, CfgNode node, Node astNode) {
            this.varName = varName;
            this.node = node;
            this.astNode = astNode;
        }

        public String varName() { return varName; }
        public CfgNode node() { return node; }
        public Node astNode() { return astNode; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VariableDefinition)) return false;
            VariableDefinition that = (VariableDefinition) o;
            return Objects.equals(varName, that.varName) &&
                    Objects.equals(node, that.node) &&
                    Objects.equals(astNode, that.astNode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(varName, node, astNode);
        }

        @Override
        public String toString() {
            return String.format("%s defined at node %d", varName, node.getId());
        }
    }

    public DataFlowGraph(ControlFlowGraph cfg, MethodDeclaration method) {
        this.cfg = cfg;
        this.method = method;
        analyze();
    }

    public ControlFlowGraph getCfg() { return cfg; }
    public MethodDeclaration getMethod() { return method; }
    public List<VariableDefinition> getAllDefinitions() { return allDefinitions; }
    public Map<CfgNode, Set<VariableDefinition>> getGen() { return gen; }
    public Map<CfgNode, Set<VariableDefinition>> getKill() { return kill; }
    public Map<CfgNode, Set<VariableDefinition>> getIn() { return in; }
    public Map<CfgNode, Set<VariableDefinition>> getOut() { return out; }

    private void analyze() {
        collectDefinitions();
        computeGenKill();
        computeReachingDefinitions();
    }

    private void collectDefinitions() {
        // 1. Method parameters are defined at ENTRY node
        for (Parameter param : method.getParameters()) {
            VariableDefinition def = new VariableDefinition(param.getNameAsString(), cfg.getEntryNode(), param);
            allDefinitions.add(def);
        }

        // 2. Local variable declarations and assignments
        for (CfgNode node : cfg.getNodes()) {
            if (node.getAstNode() == null) continue;

            node.getAstNode().walk(Node.TreeTraversal.PREORDER, astNode -> {
                if (astNode instanceof VariableDeclarator dec) {
                    VariableDefinition def = new VariableDefinition(dec.getNameAsString(), node, dec);
                    allDefinitions.add(def);
                } else if (astNode instanceof AssignExpr assign) {
                    if (assign.getTarget().isNameExpr()) {
                        String name = assign.getTarget().asNameExpr().getNameAsString();
                        VariableDefinition def = new VariableDefinition(name, node, assign);
                        allDefinitions.add(def);
                    }
                }
            });
        }
    }

    private void computeGenKill() {
        for (CfgNode node : cfg.getNodes()) {
            gen.put(node, new HashSet<>());
            kill.put(node, new HashSet<>());
        }

        // Gen
        for (VariableDefinition def : allDefinitions) {
            gen.get(def.node()).add(def);
        }

        // Kill
        for (CfgNode node : cfg.getNodes()) {
            Set<VariableDefinition> nodeGen = gen.get(node);
            for (VariableDefinition def : nodeGen) {
                // Kill all other definitions of the same variable name
                for (VariableDefinition otherDef : allDefinitions) {
                    if (otherDef.varName().equals(def.varName()) && !otherDef.equals(def)) {
                        kill.get(node).add(otherDef);
                    }
                }
            }
        }
    }

    private void computeReachingDefinitions() {
        // Initialize IN and OUT
        for (CfgNode node : cfg.getNodes()) {
            in.put(node, new HashSet<>());
            out.put(node, new HashSet<>(gen.get(node)));
        }

        Queue<CfgNode> worklist = new LinkedList<>(cfg.getNodes());

        while (!worklist.isEmpty()) {
            CfgNode node = worklist.poll();

            // IN[N] = Union of OUT[P] for all predecessors P
            Set<VariableDefinition> currentIn = new HashSet<>();
            for (CfgNode pred : cfg.getPredecessors(node)) {
                currentIn.addAll(out.get(pred));
            }
            in.put(node, currentIn);

            // OUT[N] = GEN[N] U (IN[N] - KILL[N])
            Set<VariableDefinition> oldOut = out.get(node);
            Set<VariableDefinition> newOut = new HashSet<>(gen.get(node));
            Set<VariableDefinition> inMinusKill = new HashSet<>(currentIn);
            inMinusKill.removeAll(kill.get(node));
            newOut.addAll(inMinusKill);

            if (!newOut.equals(oldOut)) {
                out.put(node, newOut);
                // Add all successors to worklist since OUT changed
                for (CfgNode succ : cfg.getSuccessors(node)) {
                    if (!worklist.contains(succ)) {
                        worklist.add(succ);
                    }
                }
            }
        }
    }

    /**
     * Gets all definitions of a variable that reach the entry of a given CfgNode.
     */
    public Set<VariableDefinition> getReachingDefinitionsAt(CfgNode node, String varName) {
        Set<VariableDefinition> reaching = new HashSet<>();
        Set<VariableDefinition> nodeIn = in.get(node);
        if (nodeIn != null) {
            for (VariableDefinition def : nodeIn) {
                if (def.varName().equals(varName)) {
                    reaching.add(def);
                }
            }
        }
        return reaching;
    }

    /**
     * Extracts variable uses in a given statement/node.
     */
    public Set<String> getUsedVariables(CfgNode node) {
        Set<String> uses = new HashSet<>();
        if (node.getAstNode() == null) return uses;

        node.getAstNode().walk(Node.TreeTraversal.PREORDER, astNode -> {
            if (astNode instanceof NameExpr nameExpr) {
                String name = nameExpr.getNameAsString();
                // Ensure it is a use, not a definition
                if (!isLhsOfAssignment(nameExpr, node.getAstNode()) && !isDeclarationName(nameExpr, node.getAstNode())) {
                    uses.add(name);
                }
            }
        });
        return uses;
    }

    private boolean isLhsOfAssignment(NameExpr nameExpr, Node root) {
        Optional<Node> parent = nameExpr.getParentNode();
        if (parent.isPresent() && parent.get() instanceof AssignExpr assign) {
            return assign.getTarget().equals(nameExpr);
        }
        return false;
    }

    private boolean isDeclarationName(NameExpr nameExpr, Node root) {
        return false;
    }
}
