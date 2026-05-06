package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.model.AnalysisSession;
import org.example.model.SourceFile;
import org.example.repository.AnalysisSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisSessionRepository sessionRepository;
    private final AnalysisClaudeService claudeService;
    private final AnalysisNotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> uploadAndAnalyze(List<MultipartFile> files) throws Exception {
        List<SourceFile> sourceFiles = new ArrayList<>();

        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            if (name != null && isRelevant(name)) {
                sourceFiles.add(new SourceFile(
                        name,
                        new String(file.getBytes(), StandardCharsets.UTF_8)
                ));
            }
        }

        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("Nu exista fisiere sursa valide.");
        }

        return analyzeSourceFiles(sourceFiles);
    }

    public Map<String, Object> analyzeSourceFiles(List<SourceFile> sourceFiles) throws Exception {
        List<Map<String, String>> fileContents = sourceFiles.stream()
                .map(file -> Map.of(
                        "name", file.name(),
                        "content", file.content()
                ))
                .toList();

        String filesJson = objectMapper.writeValueAsString(fileContents);

        AnalysisSession session = new AnalysisSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUploadedFiles(filesJson);
        session.setConversationHistory("[]");
        session.setOrchestratorCode("");
        session.setOrchestratorPseudocode("");

        sessionRepository.save(session);
        notificationService.sendAnalysisStarted(session.getSessionId());

        String analysisResult = claudeService.analyzeRepository(filesJson);
        JsonNode analysisNode = parseJson(analysisResult);

        String detectedAgentsJson = objectMapper.writeValueAsString(
                analysisNode.has("detectedAgents")
                        ? analysisNode.get("detectedAgents")
                        : objectMapper.createArrayNode()
        );

        String manualStepsJson = objectMapper.writeValueAsString(
                analysisNode.has("manualStepsDetected")
                        ? analysisNode.get("manualStepsDetected")
                        : objectMapper.createArrayNode()
        );

        String understandingSummaryJson = objectMapper.writeValueAsString(
                analysisNode.has("understandingSummary")
                        ? analysisNode.get("understandingSummary")
                        : objectMapper.createObjectNode()
        );

        String unknownsJson = objectMapper.writeValueAsString(
                analysisNode.has("unknowns")
                        ? analysisNode.get("unknowns")
                        : objectMapper.createArrayNode()
        );

        JsonNode questionsNode = analysisNode.has("questions")
                ? analysisNode.get("questions")
                : objectMapper.createArrayNode();

        if (!questionsNode.isArray() || questionsNode.isEmpty()) {
            String questionsResult = claudeService.generateQuestions(
                    detectedAgentsJson,
                    manualStepsJson,
                    understandingSummaryJson,
                    unknownsJson
            );
            JsonNode generatedQuestionsNode = parseJson(questionsResult);
            questionsNode = generatedQuestionsNode.has("questions")
                    ? generatedQuestionsNode.get("questions")
                    : objectMapper.createArrayNode();
        }

        if (!questionsNode.isArray() || questionsNode.isEmpty()) {
            throw new IllegalStateException("The LLM did not generate questions for the developer.");
        }

        String questionsJson = objectMapper.writeValueAsString(questionsNode);
        int totalQuestions = questionsNode.size();

        session.setDetectedAgents(detectedAgentsJson);
        session.setManualStepsDetected(manualStepsJson);
        session.setUnderstandingSummary(understandingSummaryJson);
        session.setUnknowns(unknownsJson);
        session.setOrchestrationQuestions(questionsJson);
        session.setEstimatedTotalQuestions(totalQuestions);

        JsonNode firstQuestion = questionsNode.get(0);
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(systemQuestionMessage(firstQuestion));

        session.setConversationHistory(objectMapper.writeValueAsString(history));
        sessionRepository.save(session);

        return Map.of(
                "sessionId", session.getSessionId(),
                "detectedAgents", readList(detectedAgentsJson),
                "manualStepsDetected", readList(manualStepsJson),
                "understandingSummary", readMap(understandingSummaryJson),
                "unknowns", readList(unknownsJson),
                "questions", objectMapper.convertValue(questionsNode, new TypeReference<List<Map<String, Object>>>() {}),
                "firstQuestion", objectMapper.convertValue(firstQuestion, new TypeReference<Map<String, Object>>() {})
        );
    }

    public Map<String, Object> getSessionState(String sessionId) throws Exception {
        AnalysisSession session = getSession(sessionId);

        List<Object> conversation = objectMapper.readValue(
                session.getConversationHistory(),
                new TypeReference<>() {}
        );

        return Map.of(
                "sessionId", session.getSessionId(),
                "status", session.getStatus().name(),
                "detectedAgents", session.getDetectedAgents() != null ? readList(session.getDetectedAgents()) : List.of(),
                "manualStepsDetected", session.getManualStepsDetected() != null ? readList(session.getManualStepsDetected()) : List.of(),
                "understandingSummary", session.getUnderstandingSummary() != null ? readMap(session.getUnderstandingSummary()) : Map.of(),
                "unknowns", session.getUnknowns() != null ? readList(session.getUnknowns()) : List.of(),
                "questions", session.getOrchestrationQuestions() != null ? readList(session.getOrchestrationQuestions()) : List.of(),
                "conversation", conversation,
                "orchestratorSoFar", session.getOrchestratorPseudocode() == null ? "" : session.getOrchestratorPseudocode(),
                "progress", Map.of(
                        "questionsAnswered", session.getQuestionsAnswered(),
                        "estimatedTotal", session.getEstimatedTotalQuestions(),
                        "percentComplete", calculatePercent(session)
                )
        );
    }

    public Map<String, Object> processAnswer(
            String sessionId,
            String questionId,
            String answer,
            String freeText
    ) throws Exception {
        AnalysisSession session = getSession(sessionId);

        if (session.getStatus() == AnalysisSession.AnalysisStatus.COMPLETE) {
            throw new IllegalStateException("Sesiunea este deja completa.");
        }
        if (session.getStatus() == AnalysisSession.AnalysisStatus.GENERATING) {
            throw new IllegalStateException("The orchestrator is already being generated.");
        }

        List<Map<String, Object>> history = objectMapper.readValue(
                session.getConversationHistory(),
                new TypeReference<>() {}
        );
        JsonNode questionsNode = objectMapper.readTree(session.getOrchestrationQuestions());

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("questionId", questionId);
        userMsg.put("text", answer + (freeText == null || freeText.isBlank() ? "" : " - " + freeText));
        history.add(userMsg);

        int newCount = session.getQuestionsAnswered() + 1;
        int totalQuestions = session.getEstimatedTotalQuestions();

        String claudeResponse = claudeService.processAnswerAndGenerateNext(
                session.getDetectedAgents(),
                session.getOrchestrationQuestions(),
                objectMapper.writeValueAsString(history),
                questionId,
                answer,
                freeText,
                newCount,
                totalQuestions,
                session.getOrchestratorPseudocode() == null ? "" : session.getOrchestratorPseudocode()
        );

        JsonNode responseNode;
        try {
            responseNode = parseJson(claudeResponse);
        } catch (Exception e) {
            log.warn("Claude returned invalid incremental JSON for {}: {}", questionId, e.getMessage());
            responseNode = fallbackIncrementalResponse(questionId, answer, freeText, newCount >= totalQuestions);
        }

        JsonNode codeNode = responseNode.get("codeGenerated");
        String language = codeNode.has("language") ? codeNode.get("language").asText() : "pseudocode";
        String explanation = codeNode.has("explanation") ? codeNode.get("explanation").asText() : "Pseudocode generated from the developer answer.";
        String code = codeNode.has("code") ? codeNode.get("code").asText() : "";

        String currentPseudocode = session.getOrchestratorPseudocode() == null ? "" : session.getOrchestratorPseudocode();
        session.setOrchestratorPseudocode(currentPseudocode + "\n\n# === " + questionId + " ===\n" + code);
        session.setOrchestratorCode(session.getOrchestratorPseudocode());
        session.setQuestionsAnswered(newCount);

        boolean isDone = newCount >= totalQuestions || (responseNode.has("isDone") && responseNode.get("isDone").asBoolean());

        Map<String, Object> sysResponse = new LinkedHashMap<>();
        sysResponse.put("role", "system");
        sysResponse.put("codeSnippetGenerated", objectMapper.convertValue(codeNode, new TypeReference<Map<String, Object>>() {}));

        JsonNode nextQ = null;
        if (!isDone && newCount < questionsNode.size()) {
            nextQ = questionsNode.get(newCount);
            sysResponse.putAll(systemQuestionMessage(nextQ));
        }

        history.add(sysResponse);
        session.setConversationHistory(objectMapper.writeValueAsString(history));

        int percent = calculatePercent(session);
        notificationService.sendCodeSnippetGenerated(sessionId, language, explanation, code, percent);

        if (isDone) {
            session.setStatus(AnalysisSession.AnalysisStatus.GENERATING);
            sessionRepository.save(session);
            new Thread(() -> generateFinalOrchestrator(sessionId)).start();
        } else {
            sessionRepository.save(session);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("codeGenerated", objectMapper.convertValue(codeNode, new TypeReference<Map<String, Object>>() {}));
        result.put("isDone", isDone);
        result.put("progress", Map.of(
                "questionsAnswered", newCount,
                "estimatedTotal", totalQuestions,
                "percentComplete", percent
        ));

        if (!isDone && nextQ != null) {
            result.put("nextQuestion", objectMapper.convertValue(nextQ, new TypeReference<Map<String, Object>>() {}));
        } else {
            result.put("nextQuestion", null);
            result.put("message", "All answers are collected. Generating the final orchestrator pseudocode with line-by-line explanations.");
        }

        return result;
    }

    public Map<String, Object> getResult(String sessionId) throws Exception {
        AnalysisSession session = getSession(sessionId);

        if (session.getStatus() == AnalysisSession.AnalysisStatus.GENERATING) {
            throw new IllegalStateException("The orchestrator is still being generated.");
        }

        if (session.getStatus() != AnalysisSession.AnalysisStatus.COMPLETE) {
            throw new IllegalStateException("The orchestrator is not ready yet.");
        }

        JsonNode resultNode = parseJson(session.getOrchestratorMarkdown());
        JsonNode pseudocodeNode = resultNode.has("orchestratorPseudocode")
                ? resultNode.get("orchestratorPseudocode")
                : objectMapper.createArrayNode();

        return Map.of(
                "sessionId", sessionId,
                "status", "COMPLETE",
                "orchestrator", Map.of(
                        "language", "pseudocode",
                        "pseudocode", objectMapper.convertValue(pseudocodeNode, new TypeReference<List<Map<String, Object>>>() {}),
                        "code", Map.of(
                                "language", "pseudocode",
                                "className", "MainAgentOrchestrator",
                                "content", session.getOrchestratorPseudocode() == null ? "" : session.getOrchestratorPseudocode()
                        ),
                        "documentation", Map.of(
                                "format", "markdown",
                                "content", resultNode.has("orchestratorMarkdown")
                                        ? resultNode.get("orchestratorMarkdown").asText()
                                        : session.getOrchestratorMarkdown()
                        ),
                        "integrationGuide", resultNode.has("integrationGuide")
                                ? objectMapper.readValue(
                                objectMapper.writeValueAsString(resultNode.get("integrationGuide")),
                                new TypeReference<List<String>>() {}
                        )
                                : List.of(),
                        "l3ToL4Summary", resultNode.has("l3ToL4Summary")
                                ? objectMapper.readValue(
                                objectMapper.writeValueAsString(resultNode.get("l3ToL4Summary")),
                                new TypeReference<Map<String, Object>>() {}
                        )
                                : Map.of()
                )
        );
    }

    private void generateFinalOrchestrator(String sessionId) {
        try {
            AnalysisSession session = getSession(sessionId);

            String finalResult = claudeService.generateFinalOrchestrator(
                    session.getDetectedAgents(),
                    session.getOrchestrationQuestions(),
                    session.getConversationHistory(),
                    session.getOrchestratorPseudocode()
            );

            JsonNode resultNode;
            try {
                resultNode = parseJson(finalResult);
            } catch (Exception e) {
                log.warn("Claude returned invalid final JSON for {}: {}", sessionId, e.getMessage());
                resultNode = fallbackFinalResult(session.getOrchestratorPseudocode());
                finalResult = objectMapper.writeValueAsString(resultNode);
            }

            if (resultNode.has("orchestratorPseudocode")) {
                session.setOrchestratorPseudocode(objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(resultNode.get("orchestratorPseudocode")));
                session.setOrchestratorCode(session.getOrchestratorPseudocode());
            }
            session.setOrchestratorMarkdown(finalResult);
            session.setStatus(AnalysisSession.AnalysisStatus.COMPLETE);
            sessionRepository.save(session);

            notificationService.sendGenerationComplete(sessionId);
        } catch (Exception e) {
            try {
                AnalysisSession session = getSession(sessionId);
                session.setStatus(AnalysisSession.AnalysisStatus.ERROR);
                sessionRepository.save(session);
            } catch (Exception ignored) {
            }

            notificationService.sendError(sessionId, e.getMessage());
        }
    }



    private JsonNode fallbackFinalResult(String accumulatedPseudocode) {
        String content = accumulatedPseudocode == null || accumulatedPseudocode.isBlank()
                ? "START Orchestrator\n  RUN detected agents in answered order\nEND Orchestrator"
                : accumulatedPseudocode;
        String[] rawLines = content.split("\\R");
        List<Map<String, Object>> lines = new ArrayList<>();
        int lineNumber = 1;
        for (String rawLine : rawLines) {
            String line = rawLine.strip();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            lines.add(Map.of(
                    "lineNumber", lineNumber++,
                    "code", line,
                    "explanation", "Line derived from incremental pseudocode built from developer answers.",
                    "source", Map.of(
                            "type", "ANSWER",
                            "detail", "Fallback final generated from accumulated pseudocode"
                    )
            ));
        }

        Map<String, Object> payload = Map.of(
                "orchestratorPseudocode", lines,
                "orchestratorMarkdown", "# Main Agent Orchestrator\n\nLocal documentation generated because the final LLM response was incomplete.",
                "integrationGuide", List.of(
                        "Place the generated main agent next to the existing agent definitions.",
                        "Map each discovered agent name to the real command, prompt, or API call.",
                        "Connect CI to run the orchestrator for repeatable code -> test -> review loops."
                ),
                "l3ToL4Summary", Map.of(
                        "manualStepsEliminated", List.of("Manual coordination between agents"),
                        "automatedNow", List.of("Explicit orchestration flow", "Validation points", "Retry-ready handoffs"),
                        "levelAchieved", "L4 - Orchestrated Agentic Workflows"
                )
        );
        return objectMapper.convertValue(payload, JsonNode.class);
    }
    private JsonNode fallbackIncrementalResponse(String questionId, String answer, String freeText, boolean isDone) {
        String cleanAnswer = answer == null || answer.isBlank() ? "developer answer" : answer.strip();
        String cleanDetails = freeText == null || freeText.isBlank() ? "" : " Details: " + freeText.strip();
        String code = "APPLY_RULE_FROM " + questionId + "\n"
                + "  USE developer_answer = \"" + cleanAnswer.replace("\"", "'") + "\"\n"
                + "  UPDATE main_agent_orchestrator WITH explicit workflow rule\n"
                + "  STORE source_mapping = ANSWER(" + questionId + ")";

        Map<String, Object> payload = Map.of(
                "codeGenerated", Map.of(
                        "language", "pseudocode",
                        "explanation", "Local fallback: Claude returned incomplete JSON, so the backend transformed the developer answer into a traceable pseudocode rule." + cleanDetails,
                        "code", code,
                        "lineExplanations", List.of(
                                Map.of(
                                        "line", "APPLY_RULE_FROM " + questionId,
                                        "explanation", "The rule comes directly from the developer answer to this question.",
                                        "source", Map.of(
                                                "type", "ANSWER",
                                                "questionId", questionId,
                                                "file", "Developer response",
                                                "detail", cleanAnswer
                                        )
                                )
                        )
                ),
                "nextQuestion", null,
                "isDone", isDone
        );
        return objectMapper.convertValue(payload, JsonNode.class);
    }
    private Map<String, Object> systemQuestionMessage(JsonNode questionNode) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "system");
        msg.put("text", questionNode.has("text") ? questionNode.get("text").asText() : "");
        msg.put("questionId", questionNode.has("id") ? questionNode.get("id").asText() : "");
        msg.put("type", questionNode.has("type") ? questionNode.get("type").asText() : "FREE_TEXT");
        if (questionNode.has("options")) {
            msg.put("options", objectMapper.convertValue(questionNode.get("options"), new TypeReference<List<Object>>() {}));
        }
        if (questionNode.has("whyAsked")) {
            msg.put("whyAsked", questionNode.get("whyAsked").asText());
        }
        if (questionNode.has("sourceHints")) {
            msg.put("sourceHints", objectMapper.convertValue(questionNode.get("sourceHints"), new TypeReference<List<Map<String, Object>>>() {}));
        }
        return msg;
    }

    private AnalysisSession getSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Sesiunea nu exista: " + sessionId));
    }

    private JsonNode parseJson(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("JSON_PARSE_FIXED_ACTIVE: Empty response from Claude API");
        }

        String cleaned = raw.strip();

        if (cleaned.startsWith("```")) {
            cleaned = cleaned
                    .replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "")
                    .replaceFirst("\\s*```\\s*$", "")
                    .strip();
        }

        int objectStart = cleaned.indexOf("{");
        int arrayStart = cleaned.indexOf("[");

        int start;
        char closingChar;
        if (objectStart == -1 && arrayStart == -1) {
            throw new IllegalArgumentException("JSON_PARSE_FIXED_ACTIVE: Claude response does not contain JSON: " + cleaned);
        } else if (objectStart == -1 || (arrayStart != -1 && arrayStart < objectStart)) {
            start = arrayStart;
            closingChar = ']';
        } else {
            start = objectStart;
            closingChar = '}';
        }

        int end = cleaned.lastIndexOf(closingChar);
        if (end < start) {
            throw new IllegalArgumentException("JSON_PARSE_FIXED_ACTIVE: Incomplete JSON response from Claude: " + cleaned);
        }

        cleaned = cleaned.substring(start, end + 1).strip();

        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON_PARSE_FIXED_ACTIVE: Cannot parse cleaned JSON: " + cleaned, e);
        }
    }

    private List<Object> readList(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<List<Object>>() {});
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private int calculatePercent(AnalysisSession session) {
        if (session.getEstimatedTotalQuestions() == 0) {
            return 0;
        }

        return (int) ((session.getQuestionsAnswered() * 100.0) / session.getEstimatedTotalQuestions());
    }

    private boolean isRelevant(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        return lower.endsWith(".java")
                || lower.endsWith(".js")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".jsx")
                || lower.endsWith(".py")
                || lower.endsWith(".kt")
                || lower.endsWith(".go")
                || lower.endsWith(".json")
                || lower.endsWith(".yaml")
                || lower.endsWith(".yml");
    }
}
