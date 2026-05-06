package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.model.SourceFile;
import org.example.service.AnalysisClaudeService;
import org.example.service.AnalysisService;
import org.example.service.GitRepositoryService;
import org.example.service.RepositorySignalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class FrontendCompatibilityController {

    private final AnalysisService analysisService;
    private final AnalysisClaudeService analysisClaudeService;
    private final GitRepositoryService gitRepositoryService;
    private final RepositorySignalService repositorySignalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentMap<String, Map<String, Object>> analysisSummaries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Map<String, Object>>> submittedAnswers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, Object>> profiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, Object>> generatedOrchestrators = new ConcurrentHashMap<>();

    @PostMapping("/analyze-repo")
    public ResponseEntity<?> analyzeRepo(@RequestBody Map<String, Object> body) {
        try {
            String repoUrl = stringValue(body.get("repoUrl"));
            String token = firstNonBlank(stringValue(body.get("token")), stringValue(body.get("accessToken")));
            String branch = firstNonBlank(stringValue(body.get("branch")), "main");

            if (repoUrl == null || repoUrl.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "repoUrl is required"));
            }

            List<SourceFile> files = gitRepositoryService.cloneAndReadSourceFiles(repoUrl, token, branch);
            if (files.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No source files found"));
            }

            Map<String, Object> summary = fastFrontendAnalysis(repoUrl, branch, files);
            analysisSummaries.put((String) summary.get("sessionId"), summary);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            String message = firstNonBlank(e.getMessage(), "Internal error");
            if (message.contains("repoUrl") || message.contains("GitHub HTTPS")) {
                return ResponseEntity.badRequest().body(Map.of("error", message));
            }
            String repoUrl = stringValue(body.get("repoUrl"));
            String branch = firstNonBlank(stringValue(body.get("branch")), "main");
            Map<String, Object> fallback = fallbackAnalysis(repoUrl, branch, message);
            analysisSummaries.put((String) fallback.get("sessionId"), fallback);
            return ResponseEntity.ok(fallback);
        } catch (Exception e) {
            String repoUrl = stringValue(body.get("repoUrl"));
            String branch = firstNonBlank(stringValue(body.get("branch")), "main");
            Map<String, Object> fallback = fallbackAnalysis(repoUrl, branch, e.getMessage());
            analysisSummaries.put((String) fallback.get("sessionId"), fallback);
            return ResponseEntity.ok(fallback);
        }
    }




    private Map<String, Object> fastFrontendAnalysis(String repoUrl, String branch, List<SourceFile> files) {
        String sessionId = "fast-" + UUID.randomUUID();
        List<Map<String, Object>> localAgents = repositorySignalService.detectAgentCandidates(files);
        if (localAgents.isEmpty()) {
            localAgents = List.of(
                    Map.of("name", "Repository Scanner", "role", "Reads source files and prepares workflow context.", "usage", "Runs during repository analysis.", "signal", "fast scan fallback")
            );
        }

        Map<String, Object> fallback = buildFastSummary(sessionId, repoUrl, branch, localAgents);

        try {
            String candidatesJson = objectMapper.writeValueAsString(Map.of(
                    "repoUrl", firstNonBlank(repoUrl, ""),
                    "branch", firstNonBlank(branch, "main"),
                    "agentCandidates", localAgents
            ));
            String rawClaude = analysisClaudeService.enrichFrontendAnalysis(repoUrl, branch, candidatesJson);
            Map<String, Object> claudeSummary = parseLooseJsonObject(rawClaude);
            return mergeClaudeAnalysis(fallback, claudeSummary);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, Object> buildFastSummary(String sessionId, String repoUrl, String branch, List<Map<String, Object>> agents) {
        List<Map<String, Object>> manualSteps = buildManualStepsForAgents(agents);
        List<Map<String, Object>> questions = defaultQuestions(agents);
        int score = Math.max(45, Math.min(78, 72 - manualSteps.size() * 5));

        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("sessionId", sessionId),
                Map.entry("repoUrl", firstNonBlank(repoUrl, "")),
                Map.entry("branch", firstNonBlank(branch, "main")),
                Map.entry("agentsDetected", agents.size()),
                Map.entry("workflow", agents.size() > 2 ? "linear" : "partial-orchestration"),
                Map.entry("reusability", "low"),
                Map.entry("orchestration", "missing"),
                Map.entry("level", "L3"),
                Map.entry("score", score),
                Map.entry("agents", agents),
                Map.entry("manualSteps", manualSteps),
                Map.entry("questions", questions),
                Map.entry("firstQuestion", questions.get(0))
        ));
    }

    private Map<String, Object> mergeClaudeAnalysis(Map<String, Object> fallback, Map<String, Object> claudeSummary) {
        Map<String, Object> merged = new LinkedHashMap<>(fallback);
        if (Boolean.TRUE.equals(claudeSummary.get("claudeFallback"))) {
            merged.put("claudeEnhanced", false);
            merged.put("claudeFallback", true);
            merged.put("fallbackReason", firstNonBlank(stringValue(claudeSummary.get("fallbackReason")), "Claude enrichment unavailable; using repository-specific local scan."));
            return merged;
        }

        List<Map<String, Object>> agents = listOfMaps(claudeSummary.get("agents"));
        if (!agents.isEmpty() && !looksLikeGenericClaudeFallback(agents)) {
            merged.put("agents", agents);
            merged.put("agentsDetected", agents.size());
        }

        List<Map<String, Object>> manualSteps = listOfMaps(claudeSummary.get("manualSteps"));
        if (!manualSteps.isEmpty()) {
            merged.put("manualSteps", manualSteps);
        }

        List<Map<String, Object>> questions = listOfMaps(claudeSummary.get("questions"));
        if (!questions.isEmpty()) {
            merged.put("questions", questions);
            merged.put("firstQuestion", questions.get(0));
        }

        merged.put("workflow", firstNonBlank(stringValue(claudeSummary.get("workflow")), stringValue(fallback.get("workflow"))));
        merged.put("reusability", firstNonBlank(stringValue(claudeSummary.get("reusability")), stringValue(fallback.get("reusability"))));
        merged.put("orchestration", firstNonBlank(stringValue(claudeSummary.get("orchestration")), stringValue(fallback.get("orchestration"))));
        merged.put("score", intValue(claudeSummary.get("score"), intValue(fallback.get("score"), 62)));
        merged.put("claudeEnhanced", true);
        return merged;
    }

    private boolean looksLikeGenericClaudeFallback(List<Map<String, Object>> agents) {
        Set<String> names = new HashSet<>();
        for (Map<String, Object> agent : agents) {
            names.add(firstNonBlank(stringValue(agent.get("name")), ""));
        }
        return names.contains("Repository Analysis Agent")
                && names.contains("Validation Agent")
                && names.contains("Developer Feedback Agent");
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLooseJsonObject(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String cleaned = raw.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned
                    .replaceFirst("^```[a-zA-Z0-9]*\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .strip();
        }
        int objectStart = cleaned.indexOf('{');
        if (objectStart > 0) {
            cleaned = cleaned.substring(objectStart).strip();
        }
        return objectMapper.readValue(cleaned, Map.class);
    }

    private List<Map<String, Object>> buildManualStepsForAgents(List<Map<String, Object>> agents) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < Math.max(1, agents.size() - 1); i++) {
            Map<String, Object> from = agents.get(i % agents.size());
            Map<String, Object> to = agents.get((i + 1) % agents.size());
            steps.add(Map.of(
                    "from", stringValue(from.get("name")),
                    "to", stringValue(to.get("name")),
                    "issue", "Developer coordinates this handoff manually today.",
                    "automation", "Make this dependency explicit in the main agent workflow."
            ));
            if (steps.size() >= 4) {
                break;
            }
        }
        return steps;
    }

    private List<Map<String, Object>> defaultQuestions(List<Map<String, Object>> agents) {
        String firstAgent = agents.isEmpty() ? "first agent" : stringValue(agents.get(0).get("name"));
        return List.of(
                Map.of(
                        "id", "q1",
                        "text", "What is the real execution order for the detected agents?",
                        "type", "ORDERING",
                        "options", List.of("Code generation -> testing -> fix -> review", firstAgent + " -> validation -> review", "Another order"),
                        "whyAsked", "The main agent needs the exact step order before it can coordinate the workflow."
                ),
                Map.of(
                        "id", "q2",
                        "text", "What should happen when a workflow step fails?",
                        "type", "CONDITION",
                        "options", List.of("Retry automatically up to 3 times", "Send feedback to the developer", "Stop the workflow"),
                        "whyAsked", "The orchestrator needs explicit retry and fallback rules."
                ),
                Map.of(
                        "id", "q3",
                        "text", "How should the final output be validated?",
                        "type", "VALIDATION",
                        "options", List.of("All critical tests pass", "Review has no critical issues", "Manual approval"),
                        "whyAsked", "Validation gates must be defined before the workflow can become reusable."
                )
        );
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureAgentsForFrontend(Map<String, Object> summary, List<SourceFile> files) {
        int agentsDetected = intValue(summary.get("agentsDetected"), 0);
        Object agentsRaw = summary.get("agents");
        boolean hasAgents = agentsRaw instanceof List<?> list && !list.isEmpty();

        if (agentsDetected > 0 && hasAgents) {
            return summary;
        }

        List<Map<String, Object>> inferredAgents = repositorySignalService.detectAgentCandidates(files);
        if (inferredAgents.isEmpty()) {
            return summary;
        }

        Map<String, Object> enriched = new LinkedHashMap<>(summary);
        enriched.put("agents", inferredAgents);
        enriched.put("agentsDetected", inferredAgents.size());
        enriched.put("workflow", inferredAgents.size() > 2 ? "linear" : "partial-orchestration");
        enriched.put("orchestration", "missing");
        enriched.put("reusability", "low");
        enriched.put("score", Math.max(45, intValue(summary.get("score"), 62)));
        return enriched;
    }

    private Map<String, Object> fallbackAnalysis(String repoUrl, String branch, String reason) {
        String sessionId = "demo-" + UUID.randomUUID();
        List<Map<String, Object>> questions = List.of(
                Map.of(
                        "id", "q1",
                        "text", "What is the real order of agents in this workflow?",
                        "type", "ORDERING",
                        "options", List.of("Code generation -> testing -> fix -> review", "Code generation -> review -> testing", "Another order"),
                        "whyAsked", "The backend used demo fallback because LLM analysis was not available: " + firstNonBlank(reason, "unknown")
                ),
                Map.of(
                        "id", "q2",
                        "text", "What should happen if testing fails?",
                        "type", "CONDITION",
                        "options", List.of("Retry automatically", "Send feedback to the developer", "Stop the workflow"),
                        "whyAsked", "The orchestrator needs a clear failure policy."
                ),
                Map.of(
                        "id", "q3",
                        "text", "How should the final output be validated?",
                        "type", "VALIDATION",
                        "options", List.of("All critical tests pass", "Review has no critical issues", "Manual approval"),
                        "whyAsked", "The main agent needs explicit validation gates."
                )
        );

        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("sessionId", sessionId),
                Map.entry("repoUrl", firstNonBlank(repoUrl, "")),
                Map.entry("branch", firstNonBlank(branch, "main")),
                Map.entry("agentsDetected", 3),
                Map.entry("workflow", "linear"),
                Map.entry("reusability", "low"),
                Map.entry("orchestration", "missing"),
                Map.entry("level", "L3"),
                Map.entry("score", 62),
                Map.entry("agents", List.of(
                        Map.of("name", "Code Builder", "role", "Generates implementation changes from tickets.", "usage", "Called after developer prepares context manually.", "signal", "fallback demo"),
                        Map.of("name", "Test Runner", "role", "Runs validation and test checks.", "usage", "Called after code generation.", "signal", "fallback demo"),
                        Map.of("name", "Review Agent", "role", "Reviews output quality and critical issues.", "usage", "Called before final approval.", "signal", "fallback demo")
                )),
                Map.entry("manualSteps", List.of(
                        Map.of("from", "Code Builder", "to", "Test Runner", "issue", "Developer coordinates code -> test manually.", "automation", "Pass generated output directly into validation."),
                        Map.of("from", "Test Runner", "to", "Review Agent", "issue", "Developer decides manually when to continue.", "automation", "Add explicit validation gates in main agent.")
                )),
                Map.entry("questions", questions),
                Map.entry("firstQuestion", questions.get(0))
        ));
    }
    @GetMapping("/analysis/{sessionId}")
    public ResponseEntity<?> getAnalysis(@PathVariable String sessionId) {
        Map<String, Object> cached = analysisSummaries.get(sessionId);
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }

        try {
            Map<String, Object> state = analysisService.getSessionState(sessionId);
            Map<String, Object> summary = toFrontendAnalysis(state, "", "main");
            analysisSummaries.put(sessionId, summary);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }
    }

    @PostMapping("/submit-answers")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> submitAnswers(@RequestBody Map<String, Object> body) {
        String sessionId = stringValue(body.get("sessionId"));
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        }

        Object answersRaw = body.get("answers");
        List<Map<String, Object>> answers = answersRaw instanceof List<?>
                ? ((List<?>) answersRaw).stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList()
                : List.of();

        submittedAnswers.put(sessionId, answers);
        Map<String, Object> profile = buildProfile(answers);
        profiles.put(sessionId, profile);
        return ResponseEntity.ok(profile);
    }

    @PostMapping("/generate-orchestrator")
    public ResponseEntity<?> generateOrchestrator(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = stringValue(body.get("sessionId"));
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
            }

            Map<String, Object> cached = generatedOrchestrators.get(sessionId);
            if (cached != null) {
                return ResponseEntity.ok(cached);
            }

            Map<String, Object> frontendResult = buildImmediateOrchestrator(sessionId);
            generatedOrchestrators.put(sessionId, frontendResult);
            return ResponseEntity.ok(frontendResult);
        } catch (Exception e) {
            String sessionId = stringValue(body.get("sessionId"));
            if (sessionId != null && !sessionId.isBlank()) {
                try {
                    Map<String, Object> fallback = buildImmediateOrchestrator(sessionId);
                    generatedOrchestrators.put(sessionId, fallback);
                    return ResponseEntity.ok(fallback);
                } catch (Exception ignored) {
                }
            }
            return ResponseEntity.internalServerError().body(Map.of("error", firstNonBlank(e.getMessage(), "Internal error")));
        }
    }

    @PostMapping("/explain-line")
    public ResponseEntity<?> explainLine(@RequestBody Map<String, Object> body) {
        String sessionId = stringValue(body.get("sessionId"));
        int lineNumber = intValue(body.get("lineNumber"), -1);
        String fallbackLine = stringValue(body.get("line"));

        Map<String, Object> orchestrator = generatedOrchestrators.get(sessionId);
        if (orchestrator != null) {
            Object linesRaw = orchestrator.get("lines");
            if (linesRaw instanceof List<?> lines) {
                for (Object item : lines) {
                    if (item instanceof Map<?, ?> lineMap && intValue(lineMap.get("lineNumber"), -2) == lineNumber) {
                        return ResponseEntity.ok(Map.of(
                                "explanation", firstNonBlank(stringValue(lineMap.get("explanation")), "Generated from repository analysis and developer answers.")
                        ));
                    }
                }
            }
        }

        return ResponseEntity.ok(Map.of(
                "explanation", "This line is part of the orchestrator generated for this session" +
                        (fallbackLine == null || fallbackLine.isBlank() ? "." : ": " + fallbackLine)
        ));
    }

    @SuppressWarnings("unchecked")
    private void completeConversationIfNeeded(String sessionId) throws Exception {
        Map<String, Object> state = analysisService.getSessionState(sessionId);
        Map<String, Object> progress = (Map<String, Object>) state.get("progress");
        int answered = intValue(progress.get("questionsAnswered"), 0);
        int total = intValue(progress.get("estimatedTotal"), 0);

        if (total == 0 || answered >= total) {
            return;
        }

        List<Map<String, Object>> questions = (List<Map<String, Object>>) state.getOrDefault("questions", List.of());
        List<Map<String, Object>> answers = submittedAnswers.getOrDefault(sessionId, List.of());

        for (int i = answered; i < total; i++) {
            Map<String, Object> question = i < questions.size() ? questions.get(i) : Map.of("id", "q" + (i + 1));
            Map<String, Object> answer = i < answers.size() ? answers.get(i) : Map.of();

            String questionId = firstNonBlank(
                    stringValue(answer.get("questionId")),
                    stringValue(question.get("id")),
                    "q" + (i + 1)
            );
            String answerText = firstNonBlank(
                    stringValue(answer.get("answer")),
                    "No explicit rule exists; use the behavior detected from the code."
            );
            String freeText = firstNonBlank(stringValue(answer.get("question")), "");

            Map<String, Object> chatResponse = analysisService.processAnswer(sessionId, questionId, answerText, freeText);
            if (Boolean.TRUE.equals(chatResponse.get("isDone"))) {
                break;
            }
        }
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> buildImmediateOrchestrator(String sessionId) throws Exception {
        String accumulated;
        try {
            Map<String, Object> state = analysisService.getSessionState(sessionId);
            accumulated = firstNonBlank(stringValue(state.get("orchestratorSoFar")), "workflow = Orchestrator(name=\"main_agent\")");
        } catch (Exception missingInternalSession) {
            accumulated = pseudocodeFromSubmittedAnswers(sessionId);
        }
        List<Map<String, Object>> lines = linesFromAccumulatedPseudocode(sessionId, accumulated);

        if (lines.isEmpty()) {
            lines = List.of(
                    Map.of(
                            "lineNumber", 1,
                            "code", "workflow = Orchestrator(name=\"main_agent\")",
                            "explanation", "Creates a single owner for the flow so developers stop coordinating agents manually.",
                            "source", Map.of("type", "INFERENCE", "detail", "Generated immediately from available session state")
                    )
            );
        }

        Map<String, Object> profile = profiles.getOrDefault(sessionId, buildProfile(submittedAnswers.getOrDefault(sessionId, List.of())));
        return Map.of(
                "targetLevel", "L4",
                "profile", profile,
                "lines", lines,
                "integrationSteps", buildIntegrationSteps(sessionId),
                "impact", buildImpact(sessionId)
        );
    }


    private String pseudocodeFromSubmittedAnswers(String sessionId) {
        List<Map<String, Object>> answers = submittedAnswers.getOrDefault(sessionId, List.of());
        Map<String, Object> analysis = analysisSummaries.getOrDefault(sessionId, Map.of());
        List<Map<String, Object>> agents = listOfMaps(analysis.get("agents"));
        List<Map<String, Object>> manualSteps = listOfMaps(analysis.get("manualSteps"));

        StringBuilder builder = new StringBuilder("workflow = Orchestrator(name=\"main_agent\")");

        if (agents.isEmpty()) {
            builder.append("\nworkflow.add_step(\"analyze\", source=\"repository\")");
        } else {
            for (Map<String, Object> agent : agents.stream().limit(5).toList()) {
                String agentName = safeCodeName(firstNonBlank(stringValue(agent.get("name")), "detected_agent"));
                String role = firstNonBlank(stringValue(agent.get("role")), "detected repository capability");
                builder.append("\nworkflow.add_step(\"")
                        .append(agentName)
                        .append("\", role=\"")
                        .append(role.replace("\"", "'"))
                        .append("\")");
            }
        }

        for (Map<String, Object> step : manualSteps.stream().limit(4).toList()) {
            String from = safeCodeName(firstNonBlank(stringValue(step.get("from")), "previous_step"));
            String to = safeCodeName(firstNonBlank(stringValue(step.get("to")), "next_step"));
            builder.append("\nworkflow.connect(\"")
                    .append(from)
                    .append("\", \"")
                    .append(to)
                    .append("\", mode=\"automated\")");
        }

        if (answers.isEmpty()) {
            builder.append("\nworkflow.add_validation(\"developer_rules_required\")");
            return builder.toString();
        }

        int index = 1;
        for (Map<String, Object> answer : answers) {
            String questionId = firstNonBlank(stringValue(answer.get("questionId")), "q" + index);
            String answerText = firstNonBlank(stringValue(answer.get("answer")), "developer rule");
            builder.append("\nworkflow.configure(\"")
                    .append(questionId.replaceAll("\\W+", "_"))
                    .append("\", rule=\"")
                    .append(answerText.replace("\"", "'"))
                    .append("\")");
            index++;
        }
        return builder.toString();
    }

    private String safeCodeName(String value) {
        String normalized = value == null ? "step" : value.strip().replaceAll("[^a-zA-Z0-9]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "").toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "step" : normalized;
    }
    private List<Map<String, Object>> linesFromAccumulatedPseudocode(String sessionId, String accumulated) {
        List<Map<String, Object>> lines = new ArrayList<>();
        String[] rawLines = accumulated.split("\\R");
        int lineNumber = 1;
        for (String rawLine : rawLines) {
            String line = rawLine.strip();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Map<String, Object> context = explanationForLine(sessionId, line);
            lines.add(Map.of(
                    "lineNumber", lineNumber++,
                    "code", line,
                    "explanation", context.get("explanation"),
                    "source", context.get("source")
            ));
            if (lines.size() >= 12) {
                break;
            }
        }
        return lines;
    }

    private Map<String, Object> explanationForLine(String sessionId, String line) {
        Map<String, Object> analysis = analysisSummaries.getOrDefault(sessionId, Map.of());
        List<Map<String, Object>> agents = listOfMaps(analysis.get("agents"));
        List<Map<String, Object>> manualSteps = listOfMaps(analysis.get("manualSteps"));
        List<Map<String, Object>> answers = submittedAnswers.getOrDefault(sessionId, List.of());

        if (line.startsWith("workflow = Orchestrator")) {
            return Map.of(
                    "explanation", "Creates the main owner for this repository flow, because the analysis found separate agent-like capabilities without one explicit controller for the whole process.",
                    "source", Map.of("type", "INFERENCE", "detail", "Derived from L3 -> L4 orchestration goal and detected repository components")
            );
        }

        if (line.startsWith("workflow.add_step")) {
            String stepName = extractQuotedValue(line, 0);
            Map<String, Object> agent = findAgentBySafeName(agents, stepName);
            String agentName = firstNonBlank(stringValue(agent.get("name")), stepName);
            String role = firstNonBlank(stringValue(agent.get("role")), "detected capability in this repository");
            String signal = firstNonBlank(stringValue(agent.get("signal")), "repository scan signal");
            return Map.of(
                    "explanation", "Adds " + agentName + " as an orchestrated step because Claude/local analysis associated it with: " + role + ". Signal used: " + signal + ".",
                    "source", Map.of("type", "CODE", "detail", "Agent candidate from repository analysis: " + agentName)
            );
        }

        if (line.startsWith("workflow.connect")) {
            String from = extractQuotedValue(line, 0);
            String to = extractQuotedValue(line, 1);
            Map<String, Object> step = findManualStep(manualSteps, from, to);
            String issue = firstNonBlank(stringValue(step.get("issue")), "manual handoff detected between these workflow steps");
            String automation = firstNonBlank(stringValue(step.get("automation")), "the main agent makes this dependency explicit");
            return Map.of(
                    "explanation", "Automates the handoff " + from + " -> " + to + ". Manual issue detected: " + issue + " Automation rule: " + automation + ".",
                    "source", Map.of("type", "INFERENCE", "detail", "Manual step detected during repository analysis")
            );
        }

        if (line.startsWith("workflow.configure")) {
            String questionId = extractQuotedValue(line, 0);
            Map<String, Object> answer = findAnswer(answers, questionId);
            String answerText = firstNonBlank(stringValue(answer.get("answer")), extractRuleFromLine(line), "developer rule");
            String questionText = firstNonBlank(stringValue(answer.get("question")), "question answered by the developer");
            return Map.of(
                    "explanation", "Configures rule " + questionId + " from the developer answer: '" + answerText + "'. It exists because the app asked: " + questionText + ".",
                    "source", Map.of("type", "ANSWER", "questionId", questionId, "detail", answerText)
            );
        }

        if (line.contains("validation")) {
            return Map.of(
                    "explanation", "Adds a validation gate because the workflow is incomplete until the developer defines what blocks or approves the final output.",
                    "source", Map.of("type", "INFERENCE", "detail", "Validation requirement inferred from unanswered orchestration questions")
            );
        }

        return Map.of(
                "explanation", "Keeps this orchestration instruction because it was generated from the current repository analysis and submitted workflow answers.",
                "source", Map.of("type", "INFERENCE", "detail", "Generated pseudocode line")
        );
    }

    private List<String> buildIntegrationSteps(String sessionId) {
        Map<String, Object> analysis = analysisSummaries.getOrDefault(sessionId, Map.of());
        List<Map<String, Object>> agents = listOfMaps(analysis.get("agents"));
        List<Map<String, Object>> manualSteps = listOfMaps(analysis.get("manualSteps"));
        List<Map<String, Object>> answers = submittedAnswers.getOrDefault(sessionId, List.of());

        String repoUrl = firstNonBlank(stringValue(analysis.get("repoUrl")), "this repository");
        String branch = firstNonBlank(stringValue(analysis.get("branch")), "main");
        String repoName = repoUrl;
        int slash = Math.max(repoName.lastIndexOf('/'), repoName.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < repoName.length()) {
            repoName = repoName.substring(slash + 1).replace(".git", "");
        }

        List<String> steps = new ArrayList<>();
        if (!agents.isEmpty()) {
            Map<String, Object> primary = agents.get(0);
            String primaryName = firstNonBlank(stringValue(primary.get("name")), "the primary detected agent");
            String primaryRole = shorten(firstNonBlank(stringValue(primary.get("role")), "detected repository capability"), 110);
            steps.add("Create `main_agent.yml` for `" + repoName + "` on branch `" + branch + "` and register `" + primaryName + "` as the entry step because it was detected as: " + primaryRole + ".");
        } else {
            steps.add("Create `main_agent.yml` for `" + repoName + "` on branch `" + branch + "` and point its first step to the repository entrypoint used to start the current workflow.");
        }

        if (!manualSteps.isEmpty()) {
            int index = 1;
            for (Map<String, Object> handoff : manualSteps.stream().limit(2).toList()) {
                String from = firstNonBlank(stringValue(handoff.get("from")), "source step");
                String to = firstNonBlank(stringValue(handoff.get("to")), "target step");
                String automation = shorten(firstNonBlank(stringValue(handoff.get("automation")), "make this dependency explicit"), 120);
                steps.add("Wire handoff " + index + ": `" + from + "` -> `" + to + "`, then encode this automation rule: " + automation + ".");
                index++;
            }
        } else {
            String names = agents.isEmpty()
                    ? "the detected workflow steps"
                    : String.join(" -> ", agents.stream().limit(3).map(agent -> firstNonBlank(stringValue(agent.get("name")), "agent")).toList());
            steps.add("Add explicit `workflow.connect` rules for the inferred sequence: " + names + ".");
        }

        if (!answers.isEmpty()) {
            Map<String, Object> answer = answers.get(0);
            String questionId = firstNonBlank(stringValue(answer.get("questionId")), "first answered rule");
            String answerText = shorten(firstNonBlank(stringValue(answer.get("answer")), "developer-provided workflow rule"), 130);
            steps.add("Persist developer rule `" + questionId + "` in the orchestrator config with value: " + answerText + ".");
        } else {
            Object firstQuestion = analysis.get("firstQuestion");
            String questionText = firstQuestion instanceof Map<?, ?> question
                    ? shorten(firstNonBlank(stringValue(question.get("text")), "the first generated workflow question"), 130)
                    : "the first generated workflow question";
            steps.add("Before enabling automation, answer and persist this repo-specific missing rule: " + questionText + ".");
        }

        int score = intValue(analysis.get("score"), 62);
        String orchestration = firstNonBlank(stringValue(analysis.get("orchestration")), "missing");
        String reusability = firstNonBlank(stringValue(analysis.get("reusability")), "unknown");
        steps.add("Run a dry run for `" + repoName + "`: verify orchestration=`" + orchestration + "`, reuse=`" + reusability + "`, and track the maturity score moving beyond " + score + "/100.");
        return steps;
    }
    private Map<String, Object> buildImpact(String sessionId) {
        Map<String, Object> analysis = analysisSummaries.getOrDefault(sessionId, Map.of());
        List<Map<String, Object>> agents = listOfMaps(analysis.get("agents"));
        List<Map<String, Object>> manualSteps = listOfMaps(analysis.get("manualSteps"));
        List<Map<String, Object>> answers = submittedAnswers.getOrDefault(sessionId, List.of());

        String firstAgent = agents.isEmpty()
                ? "the detected repository workflow"
                : firstNonBlank(stringValue(agents.get(0).get("name")), "the first detected agent");
        String firstHandoff = manualSteps.isEmpty()
                ? "the implicit developer handoff"
                : firstNonBlank(stringValue(manualSteps.get(0).get("from")), "source") + " -> " + firstNonBlank(stringValue(manualSteps.get(0).get("to")), "target");
        int score = intValue(analysis.get("score"), 62);
        String orchestration = firstNonBlank(stringValue(analysis.get("orchestration")), "missing");

        String coordination = manualSteps.isEmpty()
                ? "Coordination becomes explicit by placing " + firstAgent + " under one main agent instead of relying on an implicit developer sequence."
                : "Coordination improves by automating " + manualSteps.size() + " detected handoff(s), starting with " + firstHandoff + ".";

        String consistency = answers.isEmpty()
                ? "Consistency improves once the generated questions are answered, because retry and validation rules stop living only in the developer's head."
                : "Consistency improves because " + answers.size() + " answered rule(s) are persisted into the orchestrator instead of being repeated manually.";

        String scaling = agents.isEmpty()
                ? "Scaling improves by turning the repository workflow into named steps that can be extended later."
                : "Scaling improves because " + agents.size() + " detected capability/capabilities can now be reused as orchestrated steps instead of isolated tools.";

        return Map.of(
                "coordination", coordination,
                "consistency", consistency,
                "scaling", scaling,
                "summary", "Current repo signal: orchestration='" + orchestration + "', L3/L4 readiness score=" + score + "."
        );
    }

    private Map<String, Object> findAgentBySafeName(List<Map<String, Object>> agents, String safeName) {
        for (Map<String, Object> agent : agents) {
            if (safeCodeName(stringValue(agent.get("name"))).equals(safeName)) {
                return agent;
            }
        }
        return Map.of();
    }

    private Map<String, Object> findManualStep(List<Map<String, Object>> manualSteps, String from, String to) {
        for (Map<String, Object> step : manualSteps) {
            String stepFrom = safeCodeName(stringValue(step.get("from")));
            String stepTo = safeCodeName(stringValue(step.get("to")));
            if (stepFrom.equals(from) && stepTo.equals(to)) {
                return step;
            }
        }
        return Map.of();
    }

    private Map<String, Object> findAnswer(List<Map<String, Object>> answers, String questionId) {
        for (Map<String, Object> answer : answers) {
            String current = firstNonBlank(stringValue(answer.get("questionId")), "rule").replaceAll("\\W+", "_");
            if (current.equals(questionId)) {
                return answer;
            }
        }
        return Map.of();
    }

    private String extractQuotedValue(String line, int index) {
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"([^\\\"]*)\\\"").matcher(line);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return index < values.size() ? values.get(index) : "step";
    }

    private String extractRuleFromLine(String line) {
        int marker = line.indexOf("rule=\"");
        if (marker < 0) {
            return null;
        }
        String tail = line.substring(marker + 6);
        int end = tail.indexOf('"');
        return end >= 0 ? tail.substring(0, end) : tail;
    }
    private Map<String, Object> waitForInternalResult(String sessionId) throws Exception {
        Exception last = null;
        for (int i = 0; i < 20; i++) {
            try {
                return analysisService.getResult(sessionId);
            } catch (IllegalStateException e) {
                last = e;
                Thread.sleep(1500);
            }
        }
        throw new IllegalStateException("Orchestrator generation timed out", last);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toFrontendAnalysis(Map<String, Object> internal, String repoUrl, String branch) {
        String sessionId = stringValue(internal.get("sessionId"));
        List<Object> detectedAgents = (List<Object>) internal.getOrDefault("detectedAgents", List.of());
        List<Object> manualStepsDetected = (List<Object>) internal.getOrDefault("manualStepsDetected", List.of());
        List<Object> unknowns = (List<Object>) internal.getOrDefault("unknowns", List.of());

        String workflow = detectedAgents.size() > 2 ? "linear" : "partial-orchestration";
        String reusability = manualStepsDetected.size() > 1 ? "low" : "medium";
        String orchestration = unknowns.isEmpty() ? "partial" : "missing";
        int score = Math.max(35, Math.min(78, 70 - manualStepsDetected.size() * 6 - unknowns.size() * 3));

        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("sessionId", sessionId),
                Map.entry("repoUrl", repoUrl),
                Map.entry("branch", firstNonBlank(branch, "main")),
                Map.entry("agentsDetected", detectedAgents.size()),
                Map.entry("workflow", workflow),
                Map.entry("reusability", reusability),
                Map.entry("orchestration", orchestration),
                Map.entry("level", "L3"),
                Map.entry("score", score),
                Map.entry("agents", toFrontendAgents(detectedAgents)),
                Map.entry("manualSteps", toFrontendManualSteps(manualStepsDetected)),
                Map.entry("questions", internal.getOrDefault("questions", List.of())),
                Map.entry("firstQuestion", internal.getOrDefault("firstQuestion", Map.of()))
        ));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toFrontendAgents(List<Object> detectedAgents) {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Object item : detectedAgents) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> agent = (Map<String, Object>) raw;
                agents.add(Map.of(
                        "name", firstNonBlank(stringValue(agent.get("name")), "Unknown agent"),
                        "role", joinOrFallback(agent.get("responsibilities"), "Agent detectat in repository."),
                        "usage", "Called by " + joinOrFallback(agent.get("calledBy"), "developer workflow"),
                        "signal", joinOrFallback(agent.get("calls"), firstNonBlank(stringValue(agent.get("type")), "UNKNOWN"))
                ));
            }
        }
        return agents;
    }

    private List<Map<String, Object>> toFrontendManualSteps(List<Object> manualStepsDetected) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object item : manualStepsDetected) {
            String issue = item instanceof Map<?, ?> map
                    ? firstNonBlank(stringValue(map.get("issue")), stringValue(map.get("description")), map.toString())
                    : String.valueOf(item);
            steps.add(Map.of(
                    "from", "Developer",
                    "to", "Agent workflow",
                    "issue", issue,
                    "automation", "Turn this manual step into an explicit rule in the main agent."
            ));
        }
        return steps;
    }

    private Map<String, Object> buildProfile(List<Map<String, Object>> answers) {
        String combined = answers.stream()
                .map(answer -> stringValue(answer.get("answer")))
                .filter(Objects::nonNull)
                .reduce("", (left, right) -> left + " " + right)
                .toLowerCase(Locale.ROOT);

        String type = combined.contains("review") ? "review-heavy" : combined.contains("orchestrat") ? "partial-orchestrated" : "manual-linear";
        List<String> needs = new ArrayList<>(List.of("central orchestration", "output reuse", "validation points"));
        if (combined.contains("retry") || combined.contains("fail") || combined.contains("test")) {
            needs.add("automatic retry");
        }

        return Map.of(
                "type", type,
                "pain", "Agents are useful individually, but developers still coordinate handoffs and validation manually.",
                "needs", needs
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toFrontendOrchestrator(String sessionId, Map<String, Object> internalResult) {
        Map<String, Object> orchestrator = (Map<String, Object>) internalResult.getOrDefault("orchestrator", Map.of());
        List<Map<String, Object>> pseudocode = (List<Map<String, Object>>) orchestrator.getOrDefault("pseudocode", List.of());

        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> line : pseudocode) {
            lines.add(Map.of(
                    "lineNumber", intValue(line.get("lineNumber"), lines.size() + 1),
                    "code", firstNonBlank(stringValue(line.get("code")), "// generated orchestration step"),
                    "explanation", firstNonBlank(stringValue(line.get("explanation")), "Generated from repository analysis and developer answers."),
                    "source", line.getOrDefault("source", Map.of())
            ));
        }

        if (lines.isEmpty()) {
            lines.add(Map.of(
                    "lineNumber", 1,
                    "code", "workflow = Orchestrator(name=\"main_agent\")",
                    "explanation", "Creates a single owner for the flow so developers stop coordinating agents manually."
            ));
        }

        Map<String, Object> profile = profiles.getOrDefault(sessionId, buildProfile(submittedAnswers.getOrDefault(sessionId, List.of())));
        Object integration = buildIntegrationSteps(sessionId);

        return Map.of(
                "targetLevel", "L4",
                "profile", profile,
                "lines", lines,
                "integrationSteps", integration,
                "impact", buildImpact(sessionId)
        );
    }

    private String shorten(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxLength ? compact : compact.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String joinOrFallback(Object value, String fallback) {
        if (value instanceof Collection<?> collection && !collection.isEmpty()) {
            return String.join(", ", collection.stream().map(String::valueOf).toList());
        }
        String text = stringValue(value);
        return text == null || text.isBlank() ? fallback : text;
    }
}
