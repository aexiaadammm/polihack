package org.example.service;

import org.example.model.SourceFile;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RepositorySignalService {

    public List<Map<String, Object>> detectAgentCandidates(List<SourceFile> files) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (SourceFile file : files) {
            Candidate candidate = score(file);
            if (candidate.score < 3 || !seen.add(candidate.name)) {
                continue;
            }

            candidates.add(Map.of(
                    "name", candidate.name,
                    "role", candidate.role,
                    "usage", "Detected from repository file " + file.name(),
                    "signal", String.join(", ", candidate.signals),
                    "confidence", candidate.confidence(),
                    "evidence", candidate.signals.stream()
                            .map(signal -> Map.of(
                                    "file", file.name(),
                                    "reason", signal
                            ))
                            .toList()
            ));

            if (candidates.size() >= 8) {
                break;
            }
        }

        if (candidates.isEmpty() && !files.isEmpty()) {
            for (SourceFile file : files.stream().limit(3).toList()) {
                String name = simpleName(file.name());
                candidates.add(Map.of(
                        "name", name,
                        "role", "Relevant source component discovered in repository.",
                        "usage", "Candidate step for orchestration analysis.",
                        "signal", "source file fallback",
                        "confidence", 0.45,
                        "evidence", List.of(Map.of(
                                "file", file.name(),
                                "reason", "Fallback because no explicit agent signals were found"
                        ))
                ));
            }
        }

        return candidates;
    }

    private Candidate score(SourceFile file) {
        String simpleName = simpleName(file.name());
        String lowerName = simpleName.toLowerCase(Locale.ROOT);
        String content = file.content() == null ? "" : file.content().toLowerCase(Locale.ROOT);

        int score = 0;
        List<String> signals = new ArrayList<>();

        score += addIf(lowerName.contains("agent"), 5, signals, "class/file name contains Agent");
        score += addIf(lowerName.contains("orchestrator") || lowerName.contains("workflow"), 5, signals, "name suggests orchestration/workflow");
        score += addIf(lowerName.contains("runner") || lowerName.contains("tester") || lowerName.contains("test"), 3, signals, "name suggests testing/runner behavior");
        score += addIf(lowerName.contains("review") || lowerName.contains("validator") || lowerName.contains("validation"), 3, signals, "name suggests review/validation behavior");
        score += addIf(lowerName.contains("generator") || lowerName.contains("builder"), 3, signals, "name suggests generation/build behavior");
        score += addIf(lowerName.contains("service"), 2, signals, "service class may encapsulate workflow behavior");
        score += addIf(lowerName.contains("controller"), 1, signals, "controller exposes workflow entrypoints");

        score += addIf(content.contains("claude") || content.contains("anthropic"), 5, signals, "uses Claude/Anthropic signal");
        score += addIf(content.contains("openai") || content.contains("chat/completions") || content.contains("/v1/messages"), 5, signals, "uses LLM API signal");
        score += addIf(content.contains("systemprompt") || content.contains("userprompt") || content.contains("prompt"), 3, signals, "contains prompt construction");
        score += addIf(content.contains("retry") || content.contains("fallback"), 2, signals, "contains retry/fallback behavior");
        score += addIf(content.contains("validate") || content.contains("validation"), 2, signals, "contains validation behavior");
        score += addIf(content.contains("review"), 2, signals, "contains review behavior");
        score += addIf(content.contains("test") || content.contains("assert"), 1, signals, "contains testing signal");

        return new Candidate(simpleName, inferRole(simpleName, content, signals), score, signals);
    }

    private int addIf(boolean condition, int points, List<String> signals, String signal) {
        if (!condition) {
            return 0;
        }
        signals.add(signal);
        return points;
    }

    private String simpleName(String path) {
        String simple = path == null ? "Unknown" : path;
        int slash = Math.max(simple.lastIndexOf('/'), simple.lastIndexOf('\\'));
        if (slash >= 0) {
            simple = simple.substring(slash + 1);
        }
        int dot = simple.lastIndexOf('.');
        if (dot > 0) {
            simple = simple.substring(0, dot);
        }
        return simple.isBlank() ? "Unknown" : simple;
    }

    private String inferRole(String name, String content, List<String> signals) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("orchestrator") || lower.contains("workflow")) {
            return "Coordinates multiple agent or workflow steps.";
        }
        if (lower.contains("test") || lower.contains("runner") || content.contains("test")) {
            return "Runs validation or testing logic in the workflow.";
        }
        if (lower.contains("review") || content.contains("review")) {
            return "Reviews generated output and quality signals.";
        }
        if (lower.contains("validator") || content.contains("validation")) {
            return "Validates workflow output before completion.";
        }
        if (lower.contains("generator") || lower.contains("builder")) {
            return "Generates implementation or build artifacts.";
        }
        if (signals.stream().anyMatch(signal -> signal.contains("LLM") || signal.contains("Claude") || signal.contains("prompt"))) {
            return "Calls an LLM or prepares prompts for an AI agent.";
        }
        if (lower.contains("controller")) {
            return "Exposes workflow actions through API endpoints.";
        }
        if (lower.contains("service")) {
            return "Implements workflow behavior used by the application.";
        }
        return "Agent-like component inferred from repository signals.";
    }

    private record Candidate(String name, String role, int score, List<String> signals) {
        double confidence() {
            return Math.min(0.98, Math.max(0.45, score / 12.0));
        }
    }
}
