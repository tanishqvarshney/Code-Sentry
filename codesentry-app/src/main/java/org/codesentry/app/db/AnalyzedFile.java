package org.codesentry.app.db;

import jakarta.persistence.*;

@Entity
@Table(name = "analyzed_files")
public class AnalyzedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    public AnalyzedFile() {}

    public AnalyzedFile(Long reviewId, String filePath, String content) {
        this.reviewId = reviewId;
        this.filePath = filePath;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
