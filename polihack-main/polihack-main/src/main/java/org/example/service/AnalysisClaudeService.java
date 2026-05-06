package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AnalysisClaudeService {

    @Value("${claude.api.key}")
    private String apiKey;

    @Value("${claude.api.url}")
    private String apiUrl;

    @Value("${claude.api.model}")
    private String model;

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String analyzeRepository(String filesJson) {
        String systemPrompt = """
                You are a Senior Software Architect specialized in agentic AI systems.
                Analyze a repository where small AI agents are used separately by developers.

                Your goal is NOT to perfectly guess the workflow.
                Your goal is to clearly separate:
                1. What can be safely understood from code
                2. What remains unknown and must be asked from the developer
                3. Which questions should be sent to the frontend so the developer can complete the real process logic

                Return ONLY valid JSON, no Markdown. All user-facing strings must be in English:
                {
                  "detectedAgents": [
                    {
                      "id": "agent-1",
                      "name": "ClassOrFunctionName",
                      "file": "path/to/file.java",
                      "type": "LLM_CALLER|ORCHESTRATOR|NOTIFIER|DATA_STORE|TESTER|REVIEWER|UNKNOWN",
                      "responsibilities": ["what the agent does according to code"],
                      "inputs": ["what it receives"],
                      "outputs": ["what it produces"],
                      "calledBy": ["who calls it"],
                      "calls": ["which services/agents it calls"],
                      "evidence": [
                        {
                          "file": "path/to/file.java",
                          "reason": "method or code that supports the conclusion"
                        }
                      ]
                    }
                  ],
                  "manualStepsDetected": ["detected or likely manual step"],
                  "understandingSummary": {
                    "knownFromCode": ["clear facts extracted from code"],
                    "inferredFromCode": ["reasonable but uncertain assumptions"],
                    "missingForOrchestration": ["information required by the orchestrator"]
                  },
                  "unknowns": [
                    {
                      "id": "u1",
                      "topic": "execution order",
                      "whyItMatters": "the orchestrator must know which agent runs first"
                    }
                  ],
                  "questions": [
                    {
                      "id": "q1",
                      "text": "concrete question for the developer",
                      "type": "ORDERING|CONDITION|THRESHOLD|VALIDATION|TRIGGER|FREE_TEXT",
                      "options": ["option 1", "option 2", "Another option"],
                      "whyAsked": "which information is missing",
                      "sourceHints": [
                        {
                          "type": "CODE|INFERENCE|UNKNOWN",
                          "file": "path/to/file.java",
                          "detail": "where the question comes from"
                        }
                      ]
                    }
                  ]
                }

                Generate 3 to 6 questions. Each question must directly help build the orchestrator.
                """;

        return callClaude(systemPrompt, "Analyze these files:\n\n" + filesJson, 5000);
    }

    public String enrichFrontendAnalysis(String repoUrl, String branch, String compactCandidatesJson) {
        String systemPrompt = """
                FRONTEND_ANALYSIS_ENRICHMENT
                You are an AI workflow architect for the HIVE L3 -> L4 demo.
                You receive a compact deterministic analysis: candidate files/classes, possible roles, and code signals.

                Your mission is to personalize the analysis for the current repository, not return the same template.
                Use the agent names, signals, and evidence you receive. If a candidate is only a Service/Controller,
                describe it as an operational agent only if the signal suggests workflow, validation, testing, generation, review, retry, or LLM behavior.

                Return ONLY valid JSON, no Markdown. All user-facing strings must be in English:
                {
                  "workflow": "linear|branching|partial-orchestration|event-driven",
                  "reusability": "low|medium|high",
                  "orchestration": "missing|partial|implicit",
                  "score": 35,
                  "agents": [
                    {
                      "name": "name from repo or clearly derived name",
                      "role": "specific role observed in code",
                      "usage": "how it appears to be used in practice",
                      "signal": "concrete signal: file, class, method, keyword, LLM call, retry, validation"
                    }
                  ],
                  "manualSteps": [
                    {
                      "from": "source agent or step",
                      "to": "target agent or step",
                      "issue": "what the developer coordinates manually today",
                      "automation": "how this becomes a main-agent rule"
                    }
                  ],
                  "questions": [
                    {
                      "id": "q1",
                      "text": "repo-specific question",
                      "type": "ORDERING|CONDITION|THRESHOLD|VALIDATION|TRIGGER|FREE_TEXT",
                      "options": ["concrete option", "concrete option", "Another option"],
                      "whyAsked": "why this information is missing for orchestration"
                    }
                  ]
                }

                Rules:
                - Generate 2-6 agents, but do not invent agents unrelated to the signals.
                - Generate 2-4 manualSteps specific to the detected agent names.
                - Generate exactly 3 questions that would change the orchestrator pseudocode.
                - Avoid generic names like Code Builder/Test Runner if the repo provides better names.
                - A low score means manual L3; a higher score means implicit orchestration already exists.
                """;

        String userMessage = """
                Repository: %s
                Branch: %s

                Compact local analysis:
                %s
                """.formatted(repoUrl, branch, compactCandidatesJson);

        return callClaude(systemPrompt, userMessage, 2200);
    }
    public String generateQuestions(String detectedAgentsJson, String manualStepsJson, String understandingSummaryJson, String unknownsJson) {
        String systemPrompt = """
                You are an AI Workflow Consultant.
                Based on detected agents, manual steps, known facts, and unknowns,
                generate 3 to 6 questions for the developer.

                Return ONLY valid JSON. All user-facing strings must be in English:
                {
                  "questions": [
                    {
                      "id": "q1",
                      "text": "concrete question",
                      "type": "ORDERING|CONDITION|THRESHOLD|VALIDATION|TRIGGER|FREE_TEXT",
                      "options": ["option 1", "option 2", "Another option"],
                      "whyAsked": "why this is required for orchestration",
                      "sourceHints": [{"type": "CODE|INFERENCE|UNKNOWN", "file": "", "detail": ""}]
                    }
                  ]
                }
                """;

        String userMessage = """
                Detected agents:
                %s

                Manual steps:
                %s

                What we know so far:
                %s

                What we do not know:
                %s
                """.formatted(detectedAgentsJson, manualStepsJson, understandingSummaryJson, unknownsJson);

        return callClaude(systemPrompt, userMessage, 2000);
    }

    public String processAnswerAndGenerateNext(
            String detectedAgentsJson,
            String questionsJson,
            String conversationHistoryJson,
            String currentQuestionId,
            String answer,
            String freeText,
            int questionsAnswered,
            int totalQuestions,
            String currentPseudocode
    ) {
        boolean isLast = questionsAnswered >= totalQuestions;

        String systemPrompt = """
                You are an AI Workflow Architect.
                You incrementally build a main agent/orchestrator in PSEUDOCODE, not final Java code.

                For the current answer, generate only the pseudocode lines justified by this answer. Keep output short: code maximum 12 lines, lineExplanations maximum 5 items.
                Explain line by line where the information comes from:
                - CODE: from detected agent code
                - ANSWER: from the developer answer
                - INFERENCE: your explicit inference from code + answer

                Return ONLY valid JSON. All user-facing strings must be in English:
                {
                  "codeGenerated": {
                    "language": "pseudocode",
                    "explanation": "short explanation for the developer",
                    "code": "maximum 12 lines of pseudocode generated now",
                    "lineExplanations": [
                      {
                        "line": "RUN CodeGeneratorAgent",
                        "explanation": "why this line exists",
                        "source": {
                          "type": "CODE|ANSWER|INFERENCE",
                          "questionId": "q1",
                          "file": "Agent.java",
                          "detail": "information source"
                        }
                      }
                    ]
                  },
                  "nextQuestion": null,
                  "isDone": false
                }

                If this is not the last question, nextQuestion must be the next remaining question from the provided list.
                If this is the last question, nextQuestion is null and isDone is true.
                """;

        String userMessage = """
                Detected agents:
                %s

                Full list of questions sent to the frontend:
                %s

                Conversation history:
                %s

                Current question: %s
                Developer answer: %s
                Additional details: %s

                Accumulated pseudocode so far:
                %s

                Questions answered: %d of %d
                Is this the last question: %s
                """.formatted(
                detectedAgentsJson,
                questionsJson,
                conversationHistoryJson,
                currentQuestionId,
                answer,
                freeText == null || freeText.isBlank() ? "(no details)" : freeText,
                currentPseudocode == null || currentPseudocode.isBlank() ? "(nothing generated yet)" : currentPseudocode,
                questionsAnswered,
                totalQuestions,
                isLast ? "DA" : "NU"
        );

        return callClaude(systemPrompt, userMessage, 1800);
    }

    public String generateFinalOrchestrator(
            String detectedAgentsJson,
            String questionsJson,
            String conversationHistoryJson,
            String accumulatedPseudocode
    ) {
        String systemPrompt = """
                You are a Senior AI Workflow Architect.
                Based on agent code, questions asked to the developer, and received answers,
                generate the final main agent/orchestrator as explained pseudocode.

                For every line, explain WHERE the information came from:
                - from small-agent code
                - from a question/answer
                - from an explicit inference

                Return ONLY valid JSON. All user-facing strings must be in English:
                {
                  "orchestratorPseudocode": [
                    {
                      "lineNumber": 1,
                      "code": "START Orchestrator",
                      "explanation": "why this line exists",
                      "source": {
                        "type": "CODE|ANSWER|INFERENCE",
                        "questionId": "q1",
                        "file": "Agent.java",
                        "detail": "exact information source"
                      }
                    }
                  ],
                  "orchestratorMarkdown": "Markdown documentation for the developer",
                  "integrationGuide": ["step 1", "step 2"],
                  "l3ToL4Summary": {
                    "manualStepsEliminated": ["manual step eliminated"],
                    "automatedNow": ["what the orchestrator now coordinates"],
                    "levelAchieved": "L4 - Orchestrated Agentic Workflows"
                  }
                }
                """;

        String userMessage = """
                Detected agents:
                %s

                Questions generated for the developer:
                %s

                Full conversation with developer answers:
                %s

                Incrementally accumulated pseudocode:
                %s
                """.formatted(detectedAgentsJson, questionsJson, conversationHistoryJson, accumulatedPseudocode);

        return callClaude(systemPrompt, userMessage, 5000);
    }

    private String callClaude(String systemPrompt, String userMessage, int maxTokens) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", systemPrompt,
                    "messages", List.of(
                            Map.of("role", "user", "content", userMessage)
                    )
            );

            String response = webClient.post()
                    .uri(apiUrl)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            var jsonNode = objectMapper.readTree(response);
            return jsonNode.get("content").get(0).get("text").asText();

        } catch (WebClientResponseException e) {
            log.warn("Claude API returned {}. Falling back to deterministic demo JSON. Body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return fallbackClaudeResponse(systemPrompt);
        } catch (Exception e) {
            log.warn("Claude API unavailable. Falling back to deterministic demo JSON: {}", e.getMessage());
            return fallbackClaudeResponse(systemPrompt);
        }
    }

    private String fallbackClaudeResponse(String systemPrompt) {
        if (systemPrompt.contains("FRONTEND_ANALYSIS_ENRICHMENT")) {
            return """
                    {
                      "claudeFallback": true,
                      "fallbackReason": "Claude enrichment was unavailable; keep repository-specific local scan results."
                    }
                    """;
        }

        if (systemPrompt.contains("detectedAgents")) {
            return """
                    {
                      "detectedAgents": [
                        {
                          "id": "agent-1",
                          "name": "Code Builder",
                          "file": "fallback/demo",
                          "type": "LLM_CALLER",
                          "responsibilities": ["Generates implementation changes from developer context"],
                          "inputs": ["ticket context", "repository files"],
                          "outputs": ["generated code"],
                          "calledBy": ["Developer workflow"],
                          "calls": ["LLM API"],
                          "evidence": [{"file": "fallback/demo", "reason": "Claude API fallback scenario"}]
                        },
                        {
                          "id": "agent-2",
                          "name": "Test Runner",
                          "file": "fallback/demo",
                          "type": "TESTER",
                          "responsibilities": ["Runs validation and test checks"],
                          "inputs": ["generated code"],
                          "outputs": ["test results"],
                          "calledBy": ["Developer workflow"],
                          "calls": ["CI test command"],
                          "evidence": [{"file": "fallback/demo", "reason": "Claude API fallback scenario"}]
                        },
                        {
                          "id": "agent-3",
                          "name": "Review Agent",
                          "file": "fallback/demo",
                          "type": "REVIEWER",
                          "responsibilities": ["Reviews output quality and critical issues"],
                          "inputs": ["code", "test results"],
                          "outputs": ["review decision"],
                          "calledBy": ["Developer workflow"],
                          "calls": ["review checklist"],
                          "evidence": [{"file": "fallback/demo", "reason": "Claude API fallback scenario"}]
                        }
                      ],
                      "manualStepsDetected": [
                        "Developer coordinates code generation -> testing -> fix -> review manually",
                        "Developer decides manually when a failed test should retry",
                        "Developer manually validates whether review issues are critical"
                      ],
                      "understandingSummary": {
                        "knownFromCode": ["Repository contains agent-like workflow pieces"],
                        "inferredFromCode": ["Workflow is likely code -> test -> review"],
                        "missingForOrchestration": ["execution order", "failure policy", "validation gates"]
                      },
                      "unknowns": [
                        {"id": "u1", "topic": "execution order", "whyItMatters": "main agent needs deterministic step order"},
                        {"id": "u2", "topic": "failure handling", "whyItMatters": "main agent needs retry and escalation rules"}
                      ],
                      "questions": [
                        {
                          "id": "q1",
                          "text": "What is the real order of agents in the workflow?",
                          "type": "ORDERING",
                          "options": ["Code generation -> testing -> fix -> review", "Code generation -> review -> testing", "Another order"],
                          "whyAsked": "The orchestrator needs to know the step order.",
                          "sourceHints": [{"type": "INFERENCE", "file": "fallback/demo", "detail": "fallback question"}]
                        },
                        {
                          "id": "q2",
                          "text": "What should happen if testing fails?",
                          "type": "CONDITION",
                          "options": ["Retry automatically", "Send feedback to the developer", "Stop the workflow"],
                          "whyAsked": "The main agent needs failure-handling rules.",
                          "sourceHints": [{"type": "INFERENCE", "file": "fallback/demo", "detail": "fallback question"}]
                        },
                        {
                          "id": "q3",
                          "text": "How should the final output be validated?",
                          "type": "VALIDATION",
                          "options": ["All critical tests pass", "Review has no critical issues", "Manual approval"],
                          "whyAsked": "The orchestrator needs clear validation gates.",
                          "sourceHints": [{"type": "INFERENCE", "file": "fallback/demo", "detail": "fallback question"}]
                        }
                      ]
                    }
                    """;
        }

        if (systemPrompt.contains("codeGenerated")) {
            return """
                    {
                      "codeGenerated": {
                        "language": "pseudocode",
                        "explanation": "Local fallback: transform the developer answer into a traceable orchestration rule.",
                        "code": "workflow.apply_answer_rule()\\nworkflow.update_validation_gates()\\nworkflow.store_source_mapping(type=ANSWER)",
                        "lineExplanations": [
                          {
                            "line": "workflow.apply_answer_rule()",
                            "explanation": "This line comes from the developer answer to the current question.",
                            "source": {"type": "ANSWER", "detail": "Claude API fallback"}
                          }
                        ]
                      },
                      "nextQuestion": null,
                      "isDone": false
                    }
                    """;
        }

        if (systemPrompt.contains("orchestratorPseudocode")) {
            return """
                    {
                      "orchestratorPseudocode": [
                        {
                          "lineNumber": 1,
                          "code": "workflow = Orchestrator(name=\\\"main_agent\\\")",
                          "explanation": "Creates one owner for the complete agent workflow.",
                          "source": {"type": "INFERENCE", "detail": "Claude API fallback"}
                        },
                        {
                          "lineNumber": 2,
                          "code": "workflow.add_step(\\\"generate\\\", agent=\\\"code_builder\\\")",
                          "explanation": "Keeps code generation as an explicit step in the orchestrator.",
                          "source": {"type": "CODE", "detail": "Fallback detected agent"}
                        },
                        {
                          "lineNumber": 3,
                          "code": "workflow.add_validation(\\\"tests_and_review\\\")",
                          "explanation": "Adds validation gates from developer answers.",
                          "source": {"type": "ANSWER", "detail": "Fallback from submitted answers"}
                        }
                      ],
                      "orchestratorMarkdown": "# Main Agent Orchestrator\\n\\nFallback documentation generated because Claude API returned an error.",
                      "integrationGuide": ["Place the main agent next to existing agent definitions", "Map fallback agent names to real project calls", "Run the orchestrator from CI or frontend trigger"],
                      "l3ToL4Summary": {
                        "manualStepsEliminated": ["manual handoff between agents"],
                        "automatedNow": ["step order", "validation gates", "retry-ready flow"],
                        "levelAchieved": "L4 - Orchestrated Agentic Workflows"
                      }
                    }
                    """;
        }

        return """
                {
                  "questions": [
                    {"id": "q1", "text": "What is the real workflow order?", "type": "ORDERING", "options": ["Code -> test -> review", "Another option"], "whyAsked": "Fallback question", "sourceHints": []}
                  ]
                }
                """;
    }
}
