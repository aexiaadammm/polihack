import { AutomationFile, HiveExtensionState, OrchestratorLine, OrchestratorResult } from './messageTypes';

export function buildOrchestratorMarkdown(state: HiveExtensionState, explicitContent?: string): string {
  if (explicitContent?.trim()) {
    return explicitContent;
  }

  const orchestrator = state.orchestrator;
  const analysis = state.analysis;

  return [
    '# Hive Orchestrator',
    '',
    '## Repository',
    '',
    `- URL: ${state.repoUrl ?? analysis?.repoUrl ?? 'unknown'}`,
    `- Branch: ${state.branch ?? analysis?.branch ?? 'unknown'}`,
    `- Session: ${state.sessionId ?? 'unknown'}`,
    `- Last analyzed: ${state.lastAnalyzedAt ?? 'not analyzed'}`,
    '',
    '## Detected Agents',
    '',
    ...(state.detectedAgents.length
      ? state.detectedAgents.map((agent) => `- **${agent.name}**: ${agent.role} (${agent.signal})`)
      : ['No agents detected yet.']),
    '',
    '## Generated Orchestrator',
    '',
    formatOrchestrator(orchestrator),
    '',
    '## Repo-Ready Automation File',
    '',
    formatAutomationFile(state),
    '',
    '## Integration Steps',
    '',
    ...(orchestrator?.integrationSteps?.length
      ? orchestrator.integrationSteps.map((step) => `- ${step}`)
      : ['No integration steps generated yet.'])
  ].join('\n');
}

function formatOrchestrator(orchestrator?: OrchestratorResult): string {
  if (!orchestrator?.lines?.length) {
    return 'No orchestrator generated yet.';
  }

  return [
    '```text',
    ...orchestrator.lines.map((line) => `${line.lineNumber}. ${line.code}`),
    '```',
    '',
    ...orchestrator.lines.map((line) => `- Line ${line.lineNumber}: ${line.explanation}`)
  ].join('\n');
}

function formatAutomationFile(state: HiveExtensionState): string {
  if (!state.orchestrator) {
    return 'No automation file generated yet.';
  }

  const automationFile = state.orchestrator.automationFile ?? buildAutomationFile(state);
  return [
    `- File: \`${automationFile.fileName}\``,
    `- Recommended path: \`${automationFile.recommendedPath}\``,
    `- Language: \`${automationFile.language}\``,
    '',
    '### Automation Rules',
    '',
    ...(automationFile.rules.length ? automationFile.rules.map((rule) => `- ${rule}`) : ['- No rules generated yet.']),
    '',
    '### Generated Python',
    '',
    '```python',
    automationFile.content.trimEnd(),
    '```'
  ].join('\n');
}

function buildAutomationFile(state: HiveExtensionState): AutomationFile {
  const orchestrator = state.orchestrator;
  const lines = orchestrator?.lines ?? [];
  const stepNames = extractStepNames(state, lines);
  const rules = buildAutomationRules(state);

  return {
    fileName: 'hive_main_agent.py',
    recommendedPath: '.hive/hive_main_agent.py',
    language: 'python',
    rules,
    content: buildPythonStarter(state, stepNames, rules)
  };
}

function extractStepNames(state: HiveExtensionState, lines: OrchestratorLine[]): string[] {
  const names: string[] = [];

  for (const line of lines) {
    const code = line.code.trim();
    const quotedValues = extractQuotedValues(code);

    if (code.startsWith('workflow.add_step') && quotedValues[0]) {
      names.push(quotedValues[0]);
    }

    if (code.startsWith('workflow.connect')) {
      if (quotedValues[0]) {
        names.push(quotedValues[0]);
      }
      if (quotedValues[1]) {
        names.push(quotedValues[1]);
      }
    }
  }

  const agentNames = (state.analysis?.agents ?? state.detectedAgents ?? []).map((agent) => safePythonName(agent.name));
  const uniqueNames = [...new Set([...names.map(safePythonName), ...agentNames])].filter(Boolean);
  return uniqueNames.length ? uniqueNames.slice(0, 6) : ['repository_scanner', 'workflow_executor', 'validation_gate'];
}

function buildAutomationRules(state: HiveExtensionState): string[] {
  const orchestrator = state.orchestrator;
  const lineRules = (orchestrator?.lines ?? [])
    .map(ruleFromLine)
    .filter((rule): rule is string => Boolean(rule))
    .slice(0, 4);

  if (lineRules.length >= 4) {
    return lineRules;
  }

  for (const step of orchestrator?.integrationSteps ?? []) {
    if (lineRules.length >= 4) {
      break;
    }
    lineRules.push(shorten(step, 145));
  }

  if (lineRules.length) {
    return lineRules;
  }

  const manualSteps = state.analysis?.manualSteps ?? [];
  return manualSteps.length
    ? manualSteps.slice(0, 4).map((step) => {
        const from = String(step.from ?? 'source_step');
        const to = String(step.to ?? 'target_step');
        const automation = String(step.automation ?? step.issue ?? 'Make this handoff explicit in the main agent.');
        return `${from} -> ${to}: ${shorten(automation, 145)}`;
      })
    : ['main_agent -> repository workflow: Replace placeholders with the real commands, prompts, or API calls for each detected step.'];
}

function ruleFromLine(line: OrchestratorLine): string | null {
  const code = line.code.trim();
  const quotedValues = extractQuotedValues(code);
  const explanation = shorten(line.explanation, 135);

  if (code.startsWith('workflow.connect') && quotedValues.length >= 2) {
    return `${quotedValues[0]} -> ${quotedValues[1]}: ${explanation}`;
  }

  if (code.startsWith('workflow.add_step') && quotedValues[0]) {
    return `main_agent -> ${quotedValues[0]}: ${explanation}`;
  }

  if (code.startsWith('workflow.configure') && quotedValues[0]) {
    const configuredRule = quotedValues[1] ?? extractRuleArgument(code) ?? explanation;
    return `${quotedValues[0]}: ${shorten(configuredRule, 135)}`;
  }

  if (code.includes('validation')) {
    return `validation -> main_agent: ${explanation}`;
  }

  return explanation ? `${shorten(code, 42)}: ${explanation}` : null;
}

function buildPythonStarter(state: HiveExtensionState, stepNames: string[], rules: string[]): string {
  const repoName = safeDisplayName(repoNameFromUrl(state.repoUrl ?? state.analysis?.repoUrl) ?? 'analyzed-repository');
  const branch = safeDisplayName(state.branch ?? state.analysis?.branch ?? 'main');
  const stepConfig = buildStepConfig(state, stepNames);
  const handoffs = buildHandoffs(state);
  const validationRules = buildValidationRules(state, rules);

  return `"""
HIVE generated main agent for ${repoName}
Branch: ${branch}

This file is runnable as-is. By default every generated agent step runs a safe echo command.
To connect it to real repo tools, set environment variables named HIVE_CMD_<STEP_NAME>.
Example: HIVE_CMD_TESTER_AGENT='npm test' python .hive/hive_main_agent.py
"""

from __future__ import annotations

import json
import os
import shlex
import subprocess
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


STEP_CONFIG: list[dict[str, Any]] = ${pythonLiteral(stepConfig)}

HANDOFFS: list[dict[str, str]] = ${pythonLiteral(handoffs)}

VALIDATION_RULES: list[str] = ${pythonLiteral(validationRules)}


@dataclass
class StepResult:
    name: str
    success: bool
    attempt: int
    command: list[str]
    stdout: str = ""
    stderr: str = ""
    returncode: int = 0
    duration_seconds: float = 0.0


class MainAgent:
    def __init__(self, max_retries: int = 3, report_path: str = ".hive/hive_run_report.json") -> None:
        self.max_retries = max_retries
        self.report_path = Path(report_path)
        self.results: list[StepResult] = []
        self.context: dict[str, Any] = {"handoffs": HANDOFFS, "validation_rules": VALIDATION_RULES}

    def run(self) -> int:
        print("[HIVE] Starting generated main agent")
        print(f"[HIVE] Steps: {[step['name'] for step in STEP_CONFIG]}")
        for step in STEP_CONFIG:
            result = self.run_step_with_retry(step)
            self.results.append(result)
            if not result.success:
                self.handle_failure(result)
                self.write_report(status="failed")
                return result.returncode or 1
        self.validate_final_output()
        self.write_report(status="completed")
        print(f"[HIVE] Report written to {self.report_path}")
        return 0

    def run_step_with_retry(self, step: dict[str, Any]) -> StepResult:
        last_result: StepResult | None = None
        for attempt in range(1, self.max_retries + 1):
            result = self.run_step(step, attempt)
            if result.success:
                return result
            last_result = result
            if attempt < self.max_retries:
                time.sleep(min(2 * attempt, 6))
        assert last_result is not None
        return last_result

    def run_step(self, step: dict[str, Any], attempt: int) -> StepResult:
        name = step["name"]
        command = self.command_for(step)
        print(f"[HIVE] Running {name} attempt {attempt}: {' '.join(command)}")
        started = time.perf_counter()
        completed = subprocess.run(command, text=True, capture_output=True)
        duration = time.perf_counter() - started
        return StepResult(
            name=name,
            success=completed.returncode == 0,
            attempt=attempt,
            command=command,
            stdout=completed.stdout.strip(),
            stderr=completed.stderr.strip(),
            returncode=completed.returncode,
            duration_seconds=round(duration, 3),
        )

    def command_for(self, step: dict[str, Any]) -> list[str]:
        env_key = "HIVE_CMD_" + step["name"].upper()
        if override := os.getenv(env_key):
            return shlex.split(override)
        return list(step["command"])

    def validate_final_output(self) -> None:
        failed = [result.name for result in self.results if not result.success]
        if failed:
            raise RuntimeError(f"Validation failed because these steps failed: {failed}")
        print("[HIVE] Validation gates:")
        for rule in VALIDATION_RULES:
            print(f"  - {rule}")
        print("[HIVE] Manual handoffs encoded:")
        for handoff in HANDOFFS:
            print(f"  - {handoff['from']} -> {handoff['to']}: {handoff['automation']}")

    def handle_failure(self, result: StepResult) -> None:
        print(f"[HIVE] Workflow stopped at {result.name}")
        print(result.stderr or result.stdout or "No command output captured.")

    def write_report(self, status: str) -> None:
        self.report_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "status": status,
            "steps": [asdict(result) for result in self.results],
            "handoffs": HANDOFFS,
            "validation_rules": VALIDATION_RULES,
        }
        self.report_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(MainAgent().run())
`;
}

function buildStepConfig(state: HiveExtensionState, stepNames: string[]): Array<Record<string, unknown>> {
  const activeAgents = state.analysis?.agents ?? state.detectedAgents ?? [];
  const agentsBySafeName = new Map(activeAgents.map((agent) => [safePythonName(agent.name), agent]));

  return stepNames.map((name) => {
    const agent = agentsBySafeName.get(name);
    const fallbackLine = state.orchestrator?.lines.find((line) => line.code.includes(`"${name}"`));
    const role = agent?.role ?? fallbackLine?.explanation ?? 'Generated workflow step from Hive analysis';
    const signal = agent?.signal ?? String(fallbackLine?.source?.detail ?? 'Generated from orchestrator pseudocode');

    return {
      name,
      role: shorten(role, 160),
      signal: shorten(signal, 180),
      command: ['python', '-c', `print('${name} completed')`]
    };
  });
}

function buildHandoffs(state: HiveExtensionState): Array<Record<string, string>> {
  const connectHandoffs = (state.orchestrator?.lines ?? [])
    .filter((line) => line.code.trim().startsWith('workflow.connect'))
    .map((line) => {
      const quotedValues = extractQuotedValues(line.code);
      return {
        from: quotedValues[0] ?? 'source_step',
        to: quotedValues[1] ?? 'target_step',
        automation: shorten(line.explanation, 190)
      };
    });

  if (connectHandoffs.length) {
    return connectHandoffs.slice(0, 6);
  }

  const manualSteps = state.analysis?.manualSteps ?? [];
  if (manualSteps.length) {
    return manualSteps.slice(0, 6).map((step) => ({
      from: safePythonName(String(step.from ?? 'source_step')),
      to: safePythonName(String(step.to ?? 'target_step')),
      automation: shorten(String(step.automation ?? step.issue ?? 'Make this handoff explicit in the main agent.'), 190)
    }));
  }

  return [
    {
      from: 'main_agent',
      to: 'detected_steps',
      automation: 'Run generated steps in order, then validate output and write a machine-readable report.'
    }
  ];
}

function buildValidationRules(state: HiveExtensionState, rules: string[]): string[] {
  const configuredRules = (state.orchestrator?.lines ?? [])
    .filter((line) => line.code.trim().startsWith('workflow.configure') || line.code.includes('validation'))
    .map(ruleFromLine)
    .filter((rule): rule is string => Boolean(rule));

  const combined = [...configuredRules, ...rules, ...(state.orchestrator?.integrationSteps ?? [])];
  const unique = [...new Set(combined.map((rule) => shorten(rule, 170)))];
  return unique.length ? unique.slice(0, 6) : ['Run every configured step successfully before marking the workflow complete.'];
}

function extractQuotedValues(value: string): string[] {
  const matches = value.matchAll(/"([^"]*)"/g);
  return Array.from(matches, (match) => match[1]);
}

function extractRuleArgument(value: string): string | null {
  const match = value.match(/rule="([^"]*)"/);
  return match?.[1] ?? null;
}

function safePythonName(value: string): string {
  const normalized = value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');

  if (!normalized) {
    return 'step';
  }

  return /^\d/.test(normalized) ? `step_${normalized}` : normalized;
}

function shorten(value: string, maxLength: number): string {
  const compact = value.replace(/\s+/g, ' ').trim();
  return compact.length <= maxLength ? compact : `${compact.slice(0, maxLength - 3)}...`;
}

function pythonLiteral(value: unknown): string {
  return JSON.stringify(value, null, 4)
    .replace(/\btrue\b/g, 'True')
    .replace(/\bfalse\b/g, 'False')
    .replace(/\bnull\b/g, 'None');
}

function repoNameFromUrl(repoUrl: string | undefined | null): string | null {
  if (!repoUrl) {
    return null;
  }

  const cleanUrl = repoUrl.replace(/\.git$/i, '');
  const parts = cleanUrl.split(/[\\/]/).filter(Boolean);
  return parts.at(-1) ?? null;
}

function safeDisplayName(value: string): string {
  return value.replace(/\r?\n/g, ' ').replace(/"""/g, "'''").trim();
}
