# Hive Frontend Demo Implementation Summary

## Project Idea

Hive demonstrates the transition from L3 agent-based development to L4 orchestrated AI workflows.

At L3, developers already use individual AI agents for tasks like code generation, testing, fixing, and review. The problem is that developers still coordinate those agents manually. Hive analyzes that workflow, asks the developer a few process questions, and generates a main agent that orchestrates the existing agents into a reusable system.

## Implemented Demo Flow

The frontend now supports this complete demo flow:

1. Connect repository
2. Analyze existing agents
3. Show L3 maturity summary
4. Ask workflow questions
5. Generate a main orchestrator
6. Explain each generated code line

The demo is deterministic and does not require a backend.

## Frontend Responsibility

The frontend owns the full user-facing orchestration experience.

In the current demo, the frontend is responsible for:

- Collecting repository input from the user.
- Starting the repository analysis flow.
- Showing the detected agents, workflow maturity, and manual handoffs.
- Guiding the user through learning questions about their real development process.
- Turning each user answer into visible orchestration rules.
- Displaying the generated main agent orchestrator.
- Making every generated code line explainable through a line-by-line explanation panel.
- Keeping the flow fast, deterministic, and demo-safe even while the backend is mocked.

Right now, the frontend also simulates backend responses through `AnalysisService` so the demo can run without a live API.

When the backend is added, the frontend responsibility should stay the same from a product perspective. The only implementation change should be replacing the mock logic in `AnalysisService` with real HTTP calls.

Expected backend-backed responsibilities later:

- `POST /analyze-repo` returns repository analysis.
- `POST /submit-answers` stores or processes the user's workflow answers.
- `POST /generate-orchestrator` returns generated orchestrator code and explanations.
- `POST /explain-line` can optionally generate deeper explanations for selected lines.

So the frontend should remain focused on the user journey, state, screens, interactions, and explainability UI, while the backend handles repository scanning, LLM calls, persistence, and real orchestration generation.

## Screens

### Screen 1: Connect Repo

Route:

```text
/upload
```

What it does:

- User enters repository URL.
- User can optionally enter a GitHub token.
- User selects branch.
- Clicking the submit button starts a fake repository analysis through the frontend service.

Important change:

- GitHub token is optional.
- Only repository URL is required.

Main files:

```text
src/app/features/upload/upload-page/upload-page.ts
src/app/features/upload/upload-page/upload-page.html
src/app/features/upload/upload-page/upload-page.scss
```

### Screen 2: Analysis Summary

Route:

```text
/analysis/:sessionId
```

What it shows:

- Agents detected
- Workflow shape
- Output reusability
- L3 maturity score
- Detected agents
- Manual handoffs that can be automated

Important change:

- This page now uses Angular `signal()` state so it does not stay stuck on the loading screen.
- It also has fallback demo data, so old/direct URLs like `/analysis/demo-session-123` still work.

Main files:

```text
src/app/features/analysis/analysis-page/analysis-page.ts
src/app/features/analysis/analysis-page/analysis-page.html
src/app/features/analysis/analysis-page/analysis-page.scss
```

### Screen 3: Interactive Learning

Route:

```text
/conversation/:sessionId
```

What it does:

- Asks the developer questions about the real workflow.
- Captures how handoffs, output reuse, validation, and retries work.
- Shows a live preview of generated orchestration rules.

Questions implemented:

- Where do developers still coordinate agents manually?
- How are outputs reused between agents today?
- What must be true before the flow can finish?
- What should happen when validation fails?

Main files:

```text
src/app/features/conversation/conversation-page/conversation-page.ts
src/app/features/conversation/conversation-page/conversation-page.html
src/app/features/conversation/conversation-page/conversation-page.scss
```

### Screen 4 and 5: Generated Orchestrator and Explainability

Route:

```text
/result/:sessionId
```

What it shows:

- Generated main agent pseudocode
- Clickable code lines
- Explanation panel for each selected line
- Impact summary
- Visual Studio friendly integration steps

Important change:

- This page now uses Angular `signal()` state so it does not stay stuck on `Generating the main agent`.
- Explanations are generated with the orchestrator for a stable hackathon demo.

Main files:

```text
src/app/features/result/result-page/result-page.ts
src/app/features/result/result-page/result-page.html
src/app/features/result/result-page/result-page.scss
```

## Demo Backend Simulation

There is no real backend required right now.

All backend-like behavior is simulated in:

```text
src/app/core/services/analysis.service.ts
```

This service provides frontend contracts similar to real backend endpoints:

```text
analyzeRepository(...)
getAnalysis(...)
submitAnswers(...)
generateOrchestrator(...)
explainLine(...)
```

Later, these can be replaced with real HTTP calls to endpoints like:

```text
POST /analyze-repo
POST /submit-answers
POST /generate-orchestrator
POST /explain-line
```

## Demo Scenarios

The fake analyzer chooses between two scenarios:

### Simple Repo

Example:

```text
https://github.com/demo/agent-workflow
```

Result:

- 3 agents detected
- Linear workflow
- Low reusability
- Missing orchestration
- L3 score around 62

### More Complex Repo

Example:

```text
https://github.com/demo/agent-platform
```

or:

```text
https://github.com/demo/agent-monorepo
```

Result:

- More complex workflow
- Partial orchestration
- Medium reusability
- L3 score around 68

## Routing Added

Routes are configured in:

```text
src/app/app.routes.ts
```

Routes:

```text
/upload
/analysis/:sessionId
/conversation/:sessionId
/result/:sessionId
```

Default route redirects to:

```text
/upload
```

## Styling and Assets

Global styles were added in:

```text
src/styles.scss
```

The upload screen uses a lightweight workflow background asset:

```text
public/assets/workflow-bg.svg
```

## Bugs Fixed During Testing

### Token Required Bug

Problem:

- The UI still required a GitHub token.

Fix:

- Removed the token validation.
- Updated UI text to show the token is optional.

### Analysis Page Stuck Bug

Problem:

- The analysis screen could stay stuck on `Building the agent map`.

Fix:

- Converted analysis page state to Angular signals.
- Added fallback demo data for direct or old session URLs.

### Result Page Stuck Bug

Problem:

- The result screen could stay stuck on `Generating the main agent`.

Fix:

- Converted result page state to Angular signals.

## How To Test

Start the app:

```powershell
npm start
```

Open:

```text
http://localhost:4200/upload
```

Use:

```text
Repository URL: https://github.com/demo/agent-workflow
Token: leave empty
Branch: main
```

Then follow the UI:

1. Click `Analyze & Upgrade My Workflow`
2. Click `Help me improve`
3. Answer all questions
4. Click `Generate my orchestrator`
5. Click generated code lines to see explanations

## Verification Commands

Build:

```powershell
npm run build
```

Tests:

```powershell
npm test -- --watch=false
```

Both were passing after the latest fixes.

## Design Decision

For the hackathon demo, the frontend uses deterministic fake backend behavior instead of real OpenAI/backend calls.

Reason:

- Faster demo
- No API dependency
- No random LLM output
- Clear and explainable flow
- Easy to replace later with real backend endpoints

The architecture still keeps the backend boundary clear through `AnalysisService`, so a real Node.js API can be added without rewriting the UI flow.
