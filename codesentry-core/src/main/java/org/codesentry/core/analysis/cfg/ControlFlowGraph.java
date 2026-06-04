package org.codesentry.core.analysis.cfg;

import java.util.*;
import java.util.stream.Collectors;

public class ControlFlowGraph {
    private final CfgNode entryNode;
    private final CfgNode exitNode;
    private final Set<CfgNode> nodes = new LinkedHashSet<>();
    private final Set<CfgEdge> edges = new LinkedHashSet<>();

    public ControlFlowGraph(CfgNode entryNode, CfgNode exitNode) {
        this.entryNode = entryNode;
        this.exitNode = exitNode;
        this.nodes.add(entryNode);
        this.nodes.add(exitNode);
    }

    public CfgNode getEntryNode() { return entryNode; }
    public CfgNode getExitNode() { return exitNode; }
    public Set<CfgNode> getNodes() { return nodes; }
    public Set<CfgEdge> getEdges() { return edges; }

    public void addNode(CfgNode node) {
        nodes.add(node);
    }

    public void addEdge(CfgNode source, CfgNode target, CfgEdge.Type type) {
        nodes.add(source);
        nodes.add(target);
        CfgEdge edge = new CfgEdge(source, target, type);
        edges.add(edge);
    }

    public List<CfgNode> getSuccessors(CfgNode node) {
        return edges.stream()
                .filter(e -> e.getSource().equals(node))
                .map(CfgEdge::getTarget)
                .collect(Collectors.toList());
    }

    public List<CfgNode> getPredecessors(CfgNode node) {
        return edges.stream()
                .filter(e -> e.getTarget().equals(node))
                .map(CfgEdge::getSource)
                .collect(Collectors.toList());
    }

    public List<CfgEdge> getOutgoingEdges(CfgNode node) {
        return edges.stream()
                .filter(e -> e.getSource().equals(node))
                .collect(Collectors.toList());
    }

    public List<CfgEdge> getIncomingEdges(CfgNode node) {
        return edges.stream()
                .filter(e -> e.getTarget().equals(node))
                .collect(Collectors.toList());
    }

    public void removeEdge(CfgNode source, CfgNode target) {
        edges.removeIf(e -> e.getSource().equals(source) && e.getTarget().equals(target));
    }
}
