# Backend Contract for Hive Frontend

This document describes the API contract expected by the frontend.

The frontend currently uses mocked data inside `AnalysisService`, but these mock methods are shaped so they can be replaced with real HTTP calls later.

Main frontend integration file:

```text
src/app/core/services/analysis.service.ts
```

## Base URL

Recommended base URL:

```text
http://localhost:8080
```

The frontend can call the backend through these endpoints:

```text
POST /analyze-repo
GET /analysis/:sessionId
POST /submit-answers
POST /generate-orchestrator
POST /explain-line
```

## 1. Analyze Repository

Endpoint:

```http
POST /analyze-repo
```

Purpose:

Starts repository analysis and returns an analysis summary used by the frontend analysis page.

Request body:

```json
{
  "repoUrl": "https://github.com/org/repo",
  "branch": "main",
  "token": "optional"
}
```

Notes:

- `repoUrl` is required.
- `branch` can default to `main`.
- `token` is optional. Public repos should work without it.

Response body:

```json
{
  "sessionId": "demo-123",
  "repoUrl": "https://github.com/org/repo",
  "branch": "main",
  "agentsDetected": 3,
  "workflow": "linear",
  "reusability": "low",
  "orchestration": "missing",
  "level": "L3",
  "score": 62,
  "agents": [
    {
      "name": "Code Builder",
      "role": "Generates implementation changes from tickets.",
      "usage": "Called after the developer prepares context manually.",
      "signal": "agent + llm imports found in feature modules"
    }
  ],
  "manualSteps": [
    {
      "from": "Code Builder",
      "to": "Test Runner",
      "issue": "Developer copies generated changes into a test step.",
      "automation": "Pass generated output directly into validation."
    }
  ]
}
```

Expected value types:

```text
workflow: "linear" | "partial-orchestration"
reusability: "low" | "medium"
orchestration: "missing" | "partial"
level: "L3"
score: number
```

## 2. Get Analysis

Endpoint:

```http
GET /analysis/:sessionId
```

Purpose:

Returns the analysis summary for a session. This is useful if the user refreshes the analysis page or opens a session URL directly.

Response body:

Same shape as `POST /analyze-repo`.

If the session does not exist, return:

```json
{
  "error": "Session not found"
}
```

Recommended status:

```text
404
```

## 3. Submit Answers

Endpoint:

```http
POST /submit-answers
```

Purpose:

Stores or processes the user's workflow answers and returns a user/workflow profile.

Request body:

```json
{
  "sessionId": "demo-123",
  "answers": [
    {
      "questionId": "handoff",
      "question": "Where do developers still coordinate agents manually?",
      "answer": "Code -> tests -> fix loop"
    },
    {
      "questionId": "reuse",
      "question": "How are outputs reused between agents today?",
      "answer": "Mostly copy-paste"
    }
  ]
}
```

Response body:

```json
{
  "type": "manual-linear",
  "pain": "Agents are useful individually, but developers still coordinate every handoff.",
  "needs": [
    "central orchestration",
    "output reuse",
    "automatic retry",
    "validation points"
  ]
}
```

Expected value types:

```text
type: "manual-linear" | "review-heavy" | "partial-orchestrated"
pain: string
needs: string[]
```

## 4. Generate Orchestrator

Endpoint:

```http
POST /generate-orchestrator
```

Purpose:

Generates the final main agent/orchestrator based on repository analysis and user answers.

Request body:

```json
{
  "sessionId": "demo-123"
}
```

Response body:

```json
{
  "targetLevel": "L4",
  "profile": {
    "type": "manual-linear",
    "pain": "Agents are useful individually, but developers still coordinate every handoff.",
    "needs": [
      "central orchestration",
      "output reuse",
      "automatic retry",
      "validation points"
    ]
  },
  "lines": [
    {
      "lineNumber": 1,
      "code": "workflow = Orchestrator(name=\"main_agent\")",
      "explanation": "Creates a single owner for the flow so developers stop coordinating agents manually."
    },
    {
      "lineNumber": 2,
      "code": "workflow.add_step(\"build\", agent=\"code_builder\", input=\"ticket_context\")",
      "explanation": "Keeps code generation as the first deterministic step because the current workflow starts with implementation."
    }
  ],
  "integrationSteps": [
    "Place the generated main agent next to the existing agent definitions.",
    "Map each discovered agent name to the real command, prompt, or API call.",
    "Connect CI to run the orchestrator for repeatable code -> test -> review loops."
  ],
  "impact": {
    "coordination": "Manual handoffs are replaced by explicit dependencies.",
    "consistency": "The same validation and retry rules run every time.",
    "scaling": "New agents can be added as steps without changing the whole flow."
  }
}
```

Expected value types:

```text
targetLevel: "L4"
profile: UserProfile
lines: OrchestratorLine[]
integrationSteps: string[]
impact: {
  coordination: string
  consistency: string
  scaling: string
}
```

## 5. Explain Line

Endpoint:

```http
POST /explain-line
```

Purpose:

Returns a deeper explanation for a selected orchestrator code line.

For the current frontend, each line already includes an explanation from `/generate-orchestrator`, so this endpoint can be optional at first. It is useful later if the backend wants to generate explanations on demand.

Request body:

```json
{
  "sessionId": "demo-123",
  "lineNumber": 1,
  "line": "workflow = Orchestrator(name=\"main_agent\")"
}
```

Response body:

```json
{
  "explanation": "Creates a single owner for the flow so developers stop coordinating agents manually."
}
```

## Recommended Demo Behavior

For a stable hackathon demo, the backend can start with hardcoded scenarios.

Example logic:

```text
If repoUrl contains "platform" or "mono":
  return partial orchestration scenario
Else:
  return simple linear scenario
```

This keeps the demo fast and predictable while still looking intelligent.

## Frontend Responsibility

The frontend is responsible for:

- Collecting repository input.
- Showing analysis results.
- Guiding the user through learning questions.
- Displaying generated orchestration rules.
- Showing the final orchestrator.
- Making generated code explainable.
- Keeping the user flow fast, clear, and deterministic.

The backend is responsible for:

- Repository scanning.
- Session persistence.
- LLM calls.
- Orchestrator generation.
- Optional line-by-line explanation generation.

## Current Mock Replacement Plan

The frontend currently mocks backend behavior in:

```text
src/app/core/services/analysis.service.ts
```

To connect the real backend, replace the mock logic in these methods:

```text
analyzeRepository(...)
getAnalysis(...)
submitAnswers(...)
generateOrchestrator(...)
explainLine(...)
```

with `HttpClient` calls to the endpoints listed above.
