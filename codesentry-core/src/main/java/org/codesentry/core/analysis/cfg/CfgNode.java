package org.codesentry.core.analysis.cfg;

import com.github.javaparser.ast.Node;
import java.util.Objects;

public class CfgNode {
    private final int id;
    private final Type type;
    private final Node astNode;
    private final String label;

    public enum Type {
        ENTRY,
        EXIT,
        STATEMENT,
        CONDITION,
        TRY_BLOCK,
        CATCH_BLOCK,
        FINALLY_BLOCK
    }

    public CfgNode(int id, Type type, Node astNode, String label) {
        this.id = id;
        this.type = type;
        this.astNode = astNode;
        this.label = label;
    }

    public int getId() { return id; }
    public Type getType() { return type; }
    public Node getAstNode() { return astNode; }
    public String getLabel() { return label; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CfgNode)) return false;
        CfgNode cfgNode = (CfgNode) o;
        return id == cfgNode.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("[%d] %s: %s", id, type, label != null ? label : (astNode != null ? astNode.toString().replace("\n", " ") : ""));
    }
}
