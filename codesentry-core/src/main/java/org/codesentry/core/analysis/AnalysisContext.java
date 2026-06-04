package org.codesentry.core.analysis;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AnalysisContext {
    private List<String> filePaths;
    private Map<String, Set<Integer>> changedLines; // file -> set of 1-indexed lines changed in PR
    private Map<String, CompilationUnit> parsedAsts; // file path -> CompilationUnit AST
    private Map<String, String> fileContents; // file path -> raw file content

    public AnalysisContext(List<String> filePaths, Map<String, Set<Integer>> changedLines, Map<String, CompilationUnit> parsedAsts, Map<String, String> fileContents) {
        this.filePaths = filePaths;
        this.changedLines = changedLines;
        this.parsedAsts = parsedAsts;
        this.fileContents = fileContents;
    }

    public List<String> getFilePaths() { return filePaths; }
    public void setFilePaths(List<String> filePaths) { this.filePaths = filePaths; }

    public Map<String, Set<Integer>> getChangedLines() { return changedLines; }
    public void setChangedLines(Map<String, Set<Integer>> changedLines) { this.changedLines = changedLines; }

    public Map<String, CompilationUnit> getParsedAsts() { return parsedAsts; }
    public void setParsedAsts(Map<String, CompilationUnit> parsedAsts) { this.parsedAsts = parsedAsts; }

    public Map<String, String> getFileContents() { return fileContents; }
    public void setFileContents(Map<String, String> fileContents) { this.fileContents = fileContents; }

    /**
     * Checks if a line in a given file is directly modified by the PR diff.
     */
    public boolean isLineChanged(String file, int line) {
        if (changedLines == null) {
            return true; // If no diff info is provided, assume all lines are relevant
        }
        Set<Integer> lines = changedLines.get(file);
        return lines != null && lines.contains(line);
    }

    public static AnalysisContextBuilder builder() {
        return new AnalysisContextBuilder();
    }

    public static class AnalysisContextBuilder {
        private List<String> filePaths;
        private Map<String, Set<Integer>> changedLines;
        private Map<String, CompilationUnit> parsedAsts;
        private Map<String, String> fileContents;

        public AnalysisContextBuilder filePaths(List<String> filePaths) { this.filePaths = filePaths; return this; }
        public AnalysisContextBuilder changedLines(Map<String, Set<Integer>> changedLines) { this.changedLines = changedLines; return this; }
        public AnalysisContextBuilder parsedAsts(Map<String, CompilationUnit> parsedAsts) { this.parsedAsts = parsedAsts; return this; }
        public AnalysisContextBuilder fileContents(Map<String, String> fileContents) { this.fileContents = fileContents; return this; }

        public AnalysisContext build() {
            return new AnalysisContext(filePaths, changedLines, parsedAsts, fileContents);
        }
    }
}
