package org.codesentry.core.analysis.cfg;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;

import java.util.*;

public class CfgBuilder {
    private int nextNodeId = 0;

    private int nextId() {
        return nextNodeId++;
    }

    public ControlFlowGraph build(MethodDeclaration method) {
        nextNodeId = 0;
        CfgNode entry = new CfgNode(nextId(), CfgNode.Type.ENTRY, null, "ENTRY");
        CfgNode exit = new CfgNode(nextId(), CfgNode.Type.EXIT, null, "EXIT");
        ControlFlowGraph cfg = new ControlFlowGraph(entry, exit);

        if (method.getBody().isPresent()) {
            BlockStmt body = method.getBody().get();
            CfgNode lastNode = buildStatement(cfg, body, entry, exit, new LoopContext(), new TryContext());
            if (lastNode != null && !hasEdge(cfg, lastNode, exit)) {
                cfg.addEdge(lastNode, exit, CfgEdge.Type.UNCONDITIONAL);
            }
        } else {
            cfg.addEdge(entry, exit, CfgEdge.Type.UNCONDITIONAL);
        }

        return cfg;
    }

    private boolean hasEdge(ControlFlowGraph cfg, CfgNode src, CfgNode target) {
        return cfg.getEdges().stream().anyMatch(e -> e.getSource().equals(src) && e.getTarget().equals(target));
    }

    private CfgNode buildStatement(ControlFlowGraph cfg, Statement stmt, CfgNode entry, CfgNode exit, LoopContext loopContext, TryContext tryContext) {
        if (stmt == null) {
            return entry;
        }

        if (stmt.isBlockStmt()) {
            BlockStmt block = stmt.asBlockStmt();
            CfgNode current = entry;
            for (int i = 0; i < block.getStatements().size(); i++) {
                Statement child = block.getStatements().get(i);
                CfgNode next;
                if (i == block.getStatements().size() - 1) {
                    next = exit;
                } else {
                    next = new CfgNode(nextId(), CfgNode.Type.STATEMENT, null, "BLOCK_STEP_" + i);
                    cfg.addNode(next);
                }
                current = buildStatement(cfg, child, current, next, loopContext, tryContext);
                current = next;
            }
            return current;
        }

        if (stmt.isIfStmt()) {
            IfStmt ifStmt = stmt.asIfStmt();
            CfgNode condNode = new CfgNode(nextId(), CfgNode.Type.CONDITION, ifStmt.getCondition(), "IF: " + ifStmt.getCondition());
            cfg.addEdge(entry, condNode, CfgEdge.Type.UNCONDITIONAL);

            // True branch
            CfgNode thenExit = new CfgNode(nextId(), CfgNode.Type.STATEMENT, null, "THEN_EXIT");
            cfg.addNode(thenExit);
            CfgNode lastThen = buildStatement(cfg, ifStmt.getThenStmt(), condNode, thenExit, loopContext, tryContext);
            if (lastThen != condNode) {
                cfg.addEdge(lastThen, thenExit, CfgEdge.Type.UNCONDITIONAL);
            } else {
                cfg.addEdge(condNode, thenExit, CfgEdge.Type.TRUE);
            }
            cfg.addEdge(thenExit, exit, CfgEdge.Type.UNCONDITIONAL);

            // False branch
            CfgNode elseExit = new CfgNode(nextId(), CfgNode.Type.STATEMENT, null, "ELSE_EXIT");
            cfg.addNode(elseExit);
            if (ifStmt.getElseStmt().isPresent()) {
                CfgNode lastElse = buildStatement(cfg, ifStmt.getElseStmt().get(), condNode, elseExit, loopContext, tryContext);
                if (lastElse != condNode) {
                    cfg.addEdge(lastElse, elseExit, CfgEdge.Type.UNCONDITIONAL);
                } else {
                    cfg.addEdge(condNode, elseExit, CfgEdge.Type.FALSE);
                }
            } else {
                cfg.addEdge(condNode, elseExit, CfgEdge.Type.FALSE);
            }
            cfg.addEdge(elseExit, exit, CfgEdge.Type.UNCONDITIONAL);

            return exit;
        }

        if (stmt.isWhileStmt()) {
            WhileStmt whileStmt = stmt.asWhileStmt();
            CfgNode condNode = new CfgNode(nextId(), CfgNode.Type.CONDITION, whileStmt.getCondition(), "WHILE_COND: " + whileStmt.getCondition());
            cfg.addEdge(entry, condNode, CfgEdge.Type.UNCONDITIONAL);

            CfgNode bodyExit = new CfgNode(nextId(), CfgNode.Type.STATEMENT, null, "WHILE_BODY_EXIT");
            cfg.addNode(bodyExit);

            LoopContext newLoopContext = new LoopContext(exit, condNode);
            CfgNode lastBody = buildStatement(cfg, whileStmt.getBody(), condNode, bodyExit, newLoopContext, tryContext);
            if (lastBody != condNode) {
                cfg.addEdge(lastBody, condNode, CfgEdge.Type.UNCONDITIONAL);
            } else {
                cfg.addEdge(condNode, condNode, CfgEdge.Type.TRUE);
            }

            cfg.addEdge(condNode, exit, CfgEdge.Type.FALSE);
            return exit;
        }

        if (stmt.isForStmt()) {
            ForStmt forStmt = stmt.asForStmt();
            // Init
            CfgNode current = entry;
            for (Node init : forStmt.getInitialization()) {
                CfgNode initNode = new CfgNode(nextId(), CfgNode.Type.STATEMENT, init, "FOR_INIT: " + init);
                cfg.addEdge(current, initNode, CfgEdge.Type.UNCONDITIONAL);
                current = initNode;
            }

            // Condition
            CfgNode condNode;
            if (forStmt.getCompare().isPresent()) {
                condNode = new CfgNode(nextId(), CfgNode.Type.CONDITION, forStmt.getCompare().get(), "FOR_COND: " + forStmt.getCompare().get());
            } else {
                condNode = new CfgNode(nextId(), CfgNode.Type.CONDITION, null, "FOR_COND: true");
            }
            cfg.addEdge(current, condNode, CfgEdge.Type.UNCONDITIONAL);

            // Update node
            CfgNode updateNode = new CfgNode(nextId(), CfgNode.Type.STATEMENT, null, "FOR_UPDATE");
            cfg.addNode(updateNode);
            CfgNode currentUpdate = updateNode;
            for (Node update : forStmt.getUpdate()) {
                CfgNode updNode = new CfgNode(nextId(), CfgNode.Type.STATEMENT, update, "UPDATE: " + update);
                cfg.addEdge(currentUpdate, updNode, CfgEdge.Type.UNCONDITIONAL);
                currentUpdate = updNode;
            }
            cfg.addEdge(currentUpdate, condNode, CfgEdge.Type.UNCONDITIONAL);

            // Body
            LoopContext newLoopContext = new LoopContext(exit, updateNode);
            CfgNode lastBody = buildStatement(cfg, forStmt.getBody(), condNode, updateNode, newLoopContext, tryContext);
            if (lastBody != condNode && lastBody != updateNode) {
                cfg.addEdge(lastBody, updateNode, CfgEdge.Type.UNCONDITIONAL);
            }

            cfg.addEdge(condNode, exit, CfgEdge.Type.FALSE);
            return exit;
        }

        if (stmt.isForEachStmt()) {
            ForEachStmt forEachStmt = stmt.asForEachStmt();
            CfgNode condNode = new CfgNode(nextId(), CfgNode.Type.CONDITION, forEachStmt.getIterable(), "FOREACH_COND: " + forEachStmt.getVariable() + " in " + forEachStmt.getIterable());
            cfg.addEdge(entry, condNode, CfgEdge.Type.UNCONDITIONAL);

            LoopContext newLoopContext = new LoopContext(exit, condNode);
            CfgNode lastBody = buildStatement(cfg, forEachStmt.getBody(), condNode, condNode, newLoopContext, tryContext);
            if (lastBody != condNode) {
                cfg.addEdge(lastBody, condNode, CfgEdge.Type.UNCONDITIONAL);
            }

            cfg.addEdge(condNode, exit, CfgEdge.Type.FALSE);
            return exit;
        }

        if (stmt.isDoStmt()) {
            DoStmt doStmt = stmt.asDoStmt();
            CfgNode condNode = new CfgNode(nextId(), CfgNode.Type.CONDITION, doStmt.getCondition(), "DO_COND: " + doStmt.getCondition());
            cfg.addNode(condNode);

            LoopContext newLoopContext = new LoopContext(exit, condNode);
            CfgNode bodyEntry = new CfgNode(nextId(), CfgNode.Type.STATEMENT, null, "DO_BODY_ENTRY");
            cfg.addEdge(entry, bodyEntry, CfgEdge.Type.UNCONDITIONAL);

            CfgNode lastBody = buildStatement(cfg, doStmt.getBody(), bodyEntry, condNode, newLoopContext, tryContext);
            if (lastBody != bodyEntry) {
                cfg.addEdge(lastBody, condNode, CfgEdge.Type.UNCONDITIONAL);
            } else {
                cfg.addEdge(bodyEntry, condNode, CfgEdge.Type.UNCONDITIONAL);
            }

            cfg.addEdge(condNode, bodyEntry, CfgEdge.Type.TRUE);
            cfg.addEdge(condNode, exit, CfgEdge.Type.FALSE);
            return exit;
        }

        if (stmt.isReturnStmt()) {
            ReturnStmt returnStmt = stmt.asReturnStmt();
            CfgNode retNode = new CfgNode(nextId(), CfgNode.Type.STATEMENT, returnStmt, "RETURN: " + returnStmt);
            cfg.addEdge(entry, retNode, CfgEdge.Type.UNCONDITIONAL);

            // If there's a finally block, we flow through it before going to exit.
            if (tryContext.hasFinally()) {
                cfg.addEdge(retNode, tryContext.finallyEntry, CfgEdge.Type.UNCONDITIONAL);
            } else {
                cfg.addEdge(retNode, cfg.getExitNode(), CfgEdge.Type.UNCONDITIONAL);
            }
            return retNode;
        }

        if (stmt.isThrowStmt()) {
            ThrowStmt throwStmt = stmt.asThrowStmt();
            CfgNode throwNode = new CfgNode(nextId(), CfgNode.Type.STATEMENT, throwStmt, "THROW: " + throwStmt);
            cfg.addEdge(entry, throwNode, CfgEdge.Type.UNCONDITIONAL);

            // Flow to catch block or finally block, otherwise to exceptional exit
            if (tryContext.hasCatch()) {
                cfg.addEdge(throwNode, tryContext.catchEntry, CfgEdge.Type.EXCEPTION);
            } else if (tryContext.hasFinally()) {
                cfg.addEdge(throwNode, tryContext.finallyEntry, CfgEdge.Type.EXCEPTION);
            } else {
                cfg.addEdge(throwNode, cfg.getExitNode(), CfgEdge.Type.EXCEPTION);
            }
            return throwNode;
        }

        if (stmt.isBreakStmt()) {
            BreakStmt breakStmt = stmt.asBreakStmt();
            CfgNode breakNode = new CfgNode(nextId(), CfgNode.Type.STATEMENT, breakStmt, "BREAK");
            cfg.addEdge(entry, breakNode, CfgEdge.Type.UNCONDITIONAL);

            if (loopContext.breakTarget != null) {
                if (tryContext.hasFinally()) {
                    cfg.addEdge(breakNode, tryContext.finallyEntry, CfgEdge.Type.UNCONDITIONAL);
                } else {
                    cfg.addEdge(breakNode, loopContext.breakTarget, CfgEdge.Type.UNCONDITIONAL);
                }
            } else {
                cfg.addEdge(breakNode, exit, CfgEdge.Type.UNCONDITIONAL);
            }
            return breakNode;
        }

        if (stmt.isContinueStmt()) {
            ContinueStmt continueStmt = stmt.asContinueStmt();
            CfgNode contNode = new CfgNode(nextId(), CfgNode.Type.STATEMENT, continueStmt, "CONTINUE");
            cfg.addEdge(entry, contNode, CfgEdge.Type.UNCONDITIONAL);

            if (loopContext.continueTarget != null) {
                if (tryContext.hasFinally()) {
                    cfg.addEdge(contNode, tryContext.finallyEntry, CfgEdge.Type.UNCONDITIONAL);
                } else {
                    cfg.addEdge(contNode, loopContext.continueTarget, CfgEdge.Type.UNCONDITIONAL);
                }
            } else {
                cfg.addEdge(contNode, exit, CfgEdge.Type.UNCONDITIONAL);
            }
            return contNode;
        }

        if (stmt.isTryStmt()) {
            TryStmt tryStmt = stmt.asTryStmt();
            CfgNode tryStart = new CfgNode(nextId(), CfgNode.Type.TRY_BLOCK, tryStmt, "TRY_START");
            cfg.addEdge(entry, tryStart, CfgEdge.Type.UNCONDITIONAL);

            // Finally setup
            CfgNode finallyNode = null;
            if (tryStmt.getFinallyBlock().isPresent()) {
                finallyNode = new CfgNode(nextId(), CfgNode.Type.FINALLY_BLOCK, tryStmt.getFinallyBlock().get(), "FINALLY");
                cfg.addNode(finallyNode);
            }

            CfgNode tryExit = (finallyNode != null) ? finallyNode : exit;

            // Catch setup
            CfgNode catchStart = null;
            if (!tryStmt.getCatchClauses().isEmpty()) {
                // For simplicity, chain catches
                catchStart = new CfgNode(nextId(), CfgNode.Type.CATCH_BLOCK, null, "CATCHES");
                cfg.addNode(catchStart);
                CfgNode currentCatch = catchStart;
                for (CatchClause clause : tryStmt.getCatchClauses()) {
                    CfgNode catchClauseNode = new CfgNode(nextId(), CfgNode.Type.CATCH_BLOCK, clause, "CATCH: " + clause.getParameter());
                    cfg.addEdge(currentCatch, catchClauseNode, CfgEdge.Type.UNCONDITIONAL);
                    CfgNode clauseExit = buildStatement(cfg, clause.getBody(), catchClauseNode, tryExit, loopContext, tryContext);
                    if (clauseExit != catchClauseNode) {
                        cfg.addEdge(clauseExit, tryExit, CfgEdge.Type.UNCONDITIONAL);
                    }
                }
            }

            TryContext newTryContext = new TryContext(catchStart, finallyNode);
            CfgNode lastTry = buildStatement(cfg, tryStmt.getTryBlock(), tryStart, tryExit, loopContext, newTryContext);
            if (lastTry != tryStart && lastTry != tryExit) {
                cfg.addEdge(lastTry, tryExit, CfgEdge.Type.UNCONDITIONAL);
            }

            // If finally block exists, build it
            if (finallyNode != null) {
                CfgNode lastFinally = buildStatement(cfg, tryStmt.getFinallyBlock().get(), finallyNode, exit, loopContext, tryContext);
                if (lastFinally != finallyNode) {
                    cfg.addEdge(lastFinally, exit, CfgEdge.Type.UNCONDITIONAL);
                }
            }

            return exit;
        }

        // Generic statement fallback
        CfgNode stmtNode = new CfgNode(nextId(), CfgNode.Type.STATEMENT, stmt, stmt.getClass().getSimpleName());
        cfg.addEdge(entry, stmtNode, CfgEdge.Type.UNCONDITIONAL);
        cfg.addEdge(stmtNode, exit, CfgEdge.Type.UNCONDITIONAL);
        return stmtNode;
    }

    private static class LoopContext {
        final CfgNode breakTarget;
        final CfgNode continueTarget;

        LoopContext() {
            this.breakTarget = null;
            this.continueTarget = null;
        }

        LoopContext(CfgNode breakTarget, CfgNode continueTarget) {
            this.breakTarget = breakTarget;
            this.continueTarget = continueTarget;
        }
    }

    private static class TryContext {
        final CfgNode catchEntry;
        final CfgNode finallyEntry;

        TryContext() {
            this.catchEntry = null;
            this.finallyEntry = null;
        }

        TryContext(CfgNode catchEntry, CfgNode finallyEntry) {
            this.catchEntry = catchEntry;
            this.finallyEntry = finallyEntry;
        }

        boolean hasCatch() {
            return catchEntry != null;
        }

        boolean hasFinally() {
            return finallyEntry != null;
        }
    }
}
