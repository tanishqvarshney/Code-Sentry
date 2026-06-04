package org.codesentry.app.db;

import jakarta.persistence.*;

@Entity
@Table(name = "pr_findings")
public class PrFinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "code_snippet", length = 2000)
    private String codeSnippet;

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "rationale", length = 2000)
    private String rationale;

    @Column(name = "fix_suggestion", length = 3000)
    private String fixSuggestion;

    @Column(name = "priority_score")
    private int priorityScore;

    public PrFinding() {}

    public PrFinding(Long reviewId, String ruleId, String severity, String filePath, int lineNumber, String codeSnippet, String message, String rationale, String fixSuggestion, int priorityScore) {
        this.reviewId = reviewId;
        this.ruleId = ruleId;
        this.severity = severity;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.codeSnippet = codeSnippet;
        this.message = message;
        this.rationale = rationale;
        this.fixSuggestion = fixSuggestion;
        this.priorityScore = priorityScore;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public String getCodeSnippet() { return codeSnippet; }
    public void setCodeSnippet(String codeSnippet) { this.codeSnippet = codeSnippet; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public String getFixSuggestion() { return fixSuggestion; }
    public void setFixSuggestion(String fixSuggestion) { this.fixSuggestion = fixSuggestion; }

    public int getPriorityScore() { return priorityScore; }
    public void setPriorityScore(int priorityScore) { this.priorityScore = priorityScore; }
}
