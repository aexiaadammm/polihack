package org.example.model;

import lombok.Data;

@Data
public class RepositoryAnalysisRequest {
    private String repoUrl;
    private String accessToken;
    private String branch = "main";
}
