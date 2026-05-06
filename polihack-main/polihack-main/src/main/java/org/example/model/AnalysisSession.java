package org.example.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AnalysisSession {
    private String sessionId;
    private String uploadedFiles;
    private String detectedAgents;
    private String manualStepsDetected;
    private String understandingSummary;
    private String unknowns;
    private String orchestrationQuestions;
    private String conversationHistory;
    private String orchestratorCode;
    private String orchestratorPseudocode;
    private String orchestratorMarkdown;
    private int questionsAnswered = 0;
    private int estimatedTotalQuestions = 0;
    private AnalysisStatus status = AnalysisStatus.IN_PROGRESS;
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum AnalysisStatus {
        IN_PROGRESS, GENERATING, COMPLETE, ERROR
    }
}
