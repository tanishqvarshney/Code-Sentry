package org.codesentry.core.analysis.cfg;

import java.util.Objects;

public class CfgEdge {
    private final CfgNode source;
    private final CfgNode target;
    private final Type type;

    public enum Type {
        UNCONDITIONAL,
        TRUE,
        FALSE,
        EXCEPTION
    }

    public CfgEdge(CfgNode source, CfgNode target, Type type) {
        this.source = source;
        this.target = target;
        this.type = type;
    }

    public CfgNode getSource() { return source; }
    public CfgNode getTarget() { return target; }
    public Type getType() { return type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CfgEdge)) return false;
        CfgEdge cfgEdge = (CfgEdge) o;
        return Objects.equals(source, cfgEdge.source) &&
                Objects.equals(target, cfgEdge.target) &&
                type == cfgEdge.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, type);
    }

    @Override
    public String toString() {
        return String.format("%s -[%s]-> %s", source.getId(), type, target.getId());
    }
}
