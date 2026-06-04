package org.codesentry.app.dto;

public class ReviewRequest {
    private String type;     // "github" or "code"
    private String githubUrl;
    private String code;
    private String fileName;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getGithubUrl() { return githubUrl; }
    public void setGithubUrl(String githubUrl) { this.githubUrl = githubUrl; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
}
