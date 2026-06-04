package org.codesentry.core.model;

import java.util.List;

public class Finding {
    private String ruleId;
    private Severity severity;
    private String file;
    private int line;
    private String codeSnippet;
    private List<DataflowStep> dataflowPath;
    private String message;

    public enum Severity {
        INFO, WARNING, ERROR
    }

    public Finding() {}

    public Finding(String ruleId, Severity severity, String file, int line, String codeSnippet, List<DataflowStep> dataflowPath, String message) {
        this.ruleId = ruleId;
        this.severity = severity;
        this.file = file;
        this.line = line;
        this.codeSnippet = codeSnippet;
        this.dataflowPath = dataflowPath;
        this.message = message;
    }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public String getCodeSnippet() { return codeSnippet; }
    public void setCodeSnippet(String codeSnippet) { this.codeSnippet = codeSnippet; }

    public List<DataflowStep> getDataflowPath() { return dataflowPath; }
    public void setDataflowPath(List<DataflowStep> dataflowPath) { this.dataflowPath = dataflowPath; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public static class DataflowStep {
        private String file;
        private int line;
        private String description;

        public DataflowStep() {}

        public DataflowStep(String file, int line, String description) {
            this.file = file;
            this.line = line;
            this.description = description;
        }

        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }

        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        @Override
        public String toString() {
            return String.format("%s:%d - %s", file, line, description);
        }
    }

    public static FindingBuilder builder() {
        return new FindingBuilder();
    }

    public static class FindingBuilder {
        private String ruleId;
        private Severity severity;
        private String file;
        private int line;
        private String codeSnippet;
        private List<DataflowStep> dataflowPath;
        private String message;

        public FindingBuilder ruleId(String ruleId) { this.ruleId = ruleId; return this; }
        public FindingBuilder severity(Severity severity) { this.severity = severity; return this; }
        public FindingBuilder file(String file) { this.file = file; return this; }
        public FindingBuilder line(int line) { this.line = line; return this; }
        public FindingBuilder codeSnippet(String codeSnippet) { this.codeSnippet = codeSnippet; return this; }
        public FindingBuilder dataflowPath(List<DataflowStep> dataflowPath) { this.dataflowPath = dataflowPath; return this; }
        public FindingBuilder message(String message) { this.message = message; return this; }

        public Finding build() {
            return new Finding(ruleId, severity, file, line, codeSnippet, dataflowPath, message);
        }
    }
}
