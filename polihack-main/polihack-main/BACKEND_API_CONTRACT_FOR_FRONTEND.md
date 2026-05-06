# HIVE Backend API Contract

Base URL:

```text
http://localhost:8080
```

Content type for JSON endpoints:

```text
Content-Type: application/json
```

The frontend was mocked before. To connect it to the real backend, use the endpoints below.

---

## Recommended Frontend Flow

```text
1. POST /analyze-repo
2. Show analysis summary + questions
3. POST /submit-answers
4. POST /generate-orchestrator
5. Show generated pseudocode lines
6. Optional: POST /explain-line
```

---

## 1. Analyze Repository

```http
POST /analyze-repo
```

Starts repository analysis. The backend clones the repo, scans source files, detects agents, detects manual handoffs, and asks the LLM to generate questions for the developer.

### Request

```json
{
  "repoUrl": "https://github.com/org/repo",
  "token": "github_pat_optional"
}
```

### Fields

| Field | Required | Notes |
|---|---:|---|
| `repoUrl` | yes | Must be a GitHub HTTPS URL. |
| `token` | no | Optional for public repos. Required only for private repos. |

Branch is not required from frontend. Backend uses:

```text
main
```

### Response `200 OK`

```json
{
  "sessionId": "uuid",
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
      "name": "CodeGeneratorAgent",
      "role": "Generates implementation changes from developer context.",
      "usage": "Called by developer workflow",
      "signal": "LLM_CALLER"
    }
  ],
  "manualSteps": [
    {
      "from": "Developer",
      "to": "Agent workflow",
      "issue": "Developer manually coordinates code -> test -> fix.",
      "automation": "Transforma pasul manual intr-o regula explicita in main agent."
    }
  ],
  "questions": [
    {
      "id": "q1",
      "text": "Care este ordinea reala a agentilor in workflow?",
      "type": "ORDERING",
      "options": [
        "Code generation -> testing -> fix -> review",
        "Code generation -> review -> testing",
        "Alta varianta"
      ],
      "whyAsked": "Orchestratorul trebuie sa stie ordinea pasilor.",
      "sourceHints": [
        {
          "type": "CODE",
          "file": "src/.../Agent.java",
          "detail": "Agent detected in repository scan."
        }
      ]
    }
  ],
  "firstQuestion": {
    "id": "q1",
    "text": "Care este ordinea reala a agentilor in workflow?",
    "type": "ORDERING",
    "options": [
      "Code generation -> testing -> fix -> review",
      "Code generation -> review -> testing",
      "Alta varianta"
    ]
  }
}
```

### Response `400 Bad Request`

```json
{
  "error": "repoUrl is required"
}
```

### Frontend Notes

Use `questions` to render the question UI. `firstQuestion` exists for compatibility, but the frontend should prefer the full `questions` array.

Allowed question types:

```text
ORDERING | CONDITION | THRESHOLD | VALIDATION | TRIGGER | FREE_TEXT
```

---

## 2. Get Analysis

```http
GET /analysis/{sessionId}
```

Returns the analysis summary for an existing session.

### Response `200 OK`

Same shape as `POST /analyze-repo`.

### Response `404 Not Found`

```json
{
  "error": "Session not found"
}
```

---

## 3. Submit Answers

```http
POST /submit-answers
```

Stores the developer's answers and returns a workflow profile. The backend later uses these answers to generate the main agent/orchestrator pseudocode.

### Request

```json
{
  "sessionId": "uuid",
  "answers": [
    {
      "questionId": "q1",
      "question": "Care este ordinea reala a agentilor?",
      "answer": "Code generation -> testing -> fix -> review"
    },
    {
      "questionId": "q2",
      "question": "Ce se intampla daca testarea esueaza?",
      "answer": "Retry automat de 3 ori, apoi feedback catre developer"
    }
  ]
}
```

### Fields

| Field | Required | Notes |
|---|---:|---|
| `sessionId` | yes | Returned by `/analyze-repo`. |
| `answers` | yes | Array of answered questions. |
| `answers[].questionId` | yes | Should match question id from `questions`. |
| `answers[].question` | no | Useful for context. |
| `answers[].answer` | yes | Developer answer. |

### Response `200 OK`

```json
{
  "type": "manual-linear",
  "pain": "Agents are useful individually, but developers still coordinate handoffs and validation manually.",
  "needs": [
    "central orchestration",
    "output reuse",
    "validation points",
    "automatic retry"
  ]
}
```

### Possible `type` values

```text
manual-linear | review-heavy | partial-orchestrated
```

---

## 4. Generate Orchestrator

```http
POST /generate-orchestrator
```

Generates the final L4 main agent/orchestrator as pseudocode. The response is shaped for frontend display.

The backend uses:

- repository analysis
- detected agents
- developer answers
- generated pseudocode snippets
- fallback pseudocode if the LLM is slow or returns incomplete JSON

### Request

```json
{
  "sessionId": "uuid"
}
```

### Response `200 OK`

```json
{
  "targetLevel": "L4",
  "profile": {
    "type": "manual-linear",
    "pain": "Agents are useful individually, but developers still coordinate handoffs and validation manually.",
    "needs": [
      "central orchestration",
      "output reuse",
      "validation points",
      "automatic retry"
    ]
  },
  "lines": [
    {
      "lineNumber": 1,
      "code": "workflow = Orchestrator(name=\"main_agent\")",
      "explanation": "Creates a single owner for the flow so developers stop coordinating agents manually.",
      "source": {
        "type": "INFERENCE",
        "detail": "Generated from repository analysis and developer answers."
      }
    },
    {
      "lineNumber": 2,
      "code": "workflow.add_step(\"test\", agent=\"tester_agent\")",
      "explanation": "Keeps testing as an explicit validation point because the developer described a code -> test -> fix loop.",
      "source": {
        "type": "ANSWER",
        "questionId": "q1",
        "detail": "Developer answered: Code generation -> testing -> fix -> review"
      }
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

### Important

`/generate-orchestrator` should return `200 OK` for frontend display. If the LLM final generation is slow, backend returns an immediate fallback based on accumulated pseudocode.

### `lines[].source.type`

Possible values:

```text
CODE | ANSWER | INFERENCE
```

Meaning:

| Type | Meaning |
|---|---|
| `CODE` | The line is based on repository code / detected agent behavior. |
| `ANSWER` | The line is based on developer answers. |
| `INFERENCE` | The line is an explicit inference combining code and answers. |

---

## 5. Explain Line

```http
POST /explain-line
```

Returns a deeper explanation for a selected pseudocode line.

### Request

```json
{
  "sessionId": "uuid",
  "lineNumber": 1,
  "line": "workflow = Orchestrator(name=\"main_agent\")"
}
```

### Response `200 OK`

```json
{
  "explanation": "Creates a single owner for the flow so developers stop coordinating agents manually."
}
```

---

## Optional Legacy/Internal Endpoints

These endpoints are still available for direct backend testing, but the frontend does not need to use them.

```text
POST /api/analysis/repository
GET  /api/analysis/{sessionId}
POST /api/analysis/{sessionId}/chat
GET  /api/analysis/{sessionId}/result
GET  /api/analysis/debug/version
```

---

## Frontend Implementation Guide

Replace mocked methods in:

```text
src/app/core/services/analysis.service.ts
```

with real HTTP calls:

```typescript
analyzeRepository(payload) {
  return this.http.post(`${baseUrl}/analyze-repo`, payload);
}

getAnalysis(sessionId: string) {
  return this.http.get(`${baseUrl}/analysis/${sessionId}`);
}

submitAnswers(payload) {
  return this.http.post(`${baseUrl}/submit-answers`, payload);
}

generateOrchestrator(sessionId: string) {
  return this.http.post(`${baseUrl}/generate-orchestrator`, { sessionId });
}

explainLine(payload) {
  return this.http.post(`${baseUrl}/explain-line`, payload);
}
```

Recommended frontend base URL:

```typescript
const baseUrl = 'http://localhost:8080';
```

---

## Minimal Demo Payloads

### Public repo

```json
{
  "repoUrl": "https://github.com/spring-guides/gs-rest-service",
  "token": ""
}
```

### Private repo

```json
{
  "repoUrl": "https://github.com/org/private-repo",
  "token": "github_pat_xxx"
}
```

### Submit answers

```json
{
  "sessionId": "uuid",
  "answers": [
    {
      "questionId": "q1",
      "answer": "Code generation -> testing -> fix -> review"
    },
    {
      "questionId": "q2",
      "answer": "Retry de 3 ori, apoi feedback catre developer"
    }
  ]
}
```
