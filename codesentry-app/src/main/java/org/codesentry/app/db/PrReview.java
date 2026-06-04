package org.codesentry.app.db;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pr_reviews")
public class PrReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Column(name = "pr_number", nullable = false)
    private int prNumber;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Column(name = "status", nullable = false)
    private String status;

    public PrReview() {}

    public PrReview(String repoName, int prNumber, String commitSha, Instant analyzedAt, String status) {
        this.repoName = repoName;
        this.prNumber = prNumber;
        this.commitSha = commitSha;
        this.analyzedAt = analyzedAt;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }

    public int getPrNumber() { return prNumber; }
    public void setPrNumber(int prNumber) { this.prNumber = prNumber; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public Instant getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(Instant analyzedAt) { this.analyzedAt = analyzedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
