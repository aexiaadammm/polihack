package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    private void send(String sessionId, Map<String, Object> payload) {
        String destination = "/topic/analysis/" + sessionId + "/status";
        messagingTemplate.convertAndSend(destination, (Object) payload);
        log.info("WebSocket -> {} type={}", destination, payload.get("type"));
    }

    public void sendAnalysisStarted(String sessionId) {
        send(sessionId, Map.of(
                "type", "ANALYSIS_STARTED",
                "sessionId", sessionId,
                "message", "Analiza repository-ului a inceput."
        ));
    }

    public void sendCodeSnippetGenerated(String sessionId, String language,
                                         String explanation, String code, int progressPercent) {
        send(sessionId, Map.of(
                "type", "CODE_SNIPPET_GENERATED",
                "sessionId", sessionId,
                "snippet", Map.of(
                        "language", language,
                        "explanation", explanation,
                        "code", code
                ),
                "progress", progressPercent
        ));
    }

    public void sendGenerationComplete(String sessionId) {
        send(sessionId, Map.of(
                "type", "GENERATION_COMPLETE",
                "sessionId", sessionId,
                "message", "The orchestrator has been generated."
        ));
    }

    public void sendError(String sessionId, String errorMessage) {
        send(sessionId, Map.of(
                "type", "ERROR",
                "sessionId", sessionId,
                "message", "Error: " + errorMessage
        ));
    }
}
