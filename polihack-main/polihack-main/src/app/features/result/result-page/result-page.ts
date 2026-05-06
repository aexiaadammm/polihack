import { Component, OnInit, computed, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {
  AnalysisService,
  AutomationFile,
  OrchestratorLine,
  OrchestratorResult,
} from '../../../core/services/analysis.service';

@Component({
  selector: 'app-result-page',
  imports: [],
  templateUrl: './result-page.html',
  styleUrl: './result-page.scss',
})
export class ResultPage implements OnInit {
  readonly orchestrator = signal<OrchestratorResult | null>(null);
  readonly selectedLine = signal<OrchestratorLine | null>(null);
  readonly loading = signal(true);
  readonly explanation = signal('');
  readonly errorMessage = signal('');
  readonly automationFile = computed(() => {
    const orchestrator = this.orchestrator();
    return orchestrator ? (orchestrator.automationFile ?? this.buildAutomationFile(orchestrator)) : null;
  });

  private sessionId = '';

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly analysisService: AnalysisService,
  ) {}

  ngOnInit(): void {
    this.sessionId = this.route.snapshot.paramMap.get('sessionId') ?? '';
    this.analysisService.generateOrchestrator(this.sessionId).subscribe({
      next: (result) => {
        this.orchestrator.set(result);
        this.selectedLine.set(result.lines[0] ?? null);
        this.explanation.set(result.lines[0]?.explanation ?? 'Backend returned no pseudocode lines.');
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('Could not generate orchestrator. Check POST /generate-orchestrator.');
        this.loading.set(false);
      },
    });
  }

  selectLine(line: OrchestratorLine): void {
    this.selectedLine.set(line);
    this.analysisService.explainLine(this.sessionId, line).subscribe({
      next: (response) => {
        this.explanation.set(response.explanation);
      },
      error: () => {
        this.explanation.set(line.explanation || 'Could not load deeper explanation from POST /explain-line.');
      },
    });
  }

  restart(): void {
    this.router.navigate(['/upload']);
  }

  private buildAutomationFile(orchestrator: OrchestratorResult): AutomationFile {
    const stepNames = this.extractStepNames(orchestrator.lines);
    const rules = this.buildAutomationRules(orchestrator);

    return {
      fileName: 'hive_main_agent.py',
      recommendedPath: '.hive/hive_main_agent.py',
      language: 'python',
      rules,
      content: this.buildPythonStarter(orchestrator, stepNames, rules),
    };
  }

  private extractStepNames(lines: OrchestratorLine[]): string[] {
    const names: string[] = [];

    for (const line of lines) {
      const code = line.code.trim();
      const quotedValues = this.extractQuotedValues(code);

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

    const uniqueNames = Array.from(new Set(names.map((name) => this.safePythonName(name))));
    return uniqueNames.length ? uniqueNames.slice(0, 6) : ['repository_scanner', 'workflow_executor', 'validation_gate'];
  }

  private buildAutomationRules(orchestrator: OrchestratorResult): string[] {
    const rules = orchestrator.lines
      .map((line) => this.ruleFromLine(line))
      .filter((rule): rule is string => Boolean(rule))
      .slice(0, 4);

    if (rules.length >= 4) {
      return rules;
    }

    for (const step of orchestrator.integrationSteps) {
      if (rules.length >= 4) {
        break;
      }
      rules.push(this.shorten(step, 145));
    }

    return rules.length
      ? rules
      : ['main_agent -> repository workflow: Replace placeholders with the real commands, prompts, or API calls for each detected step.'];
  }

  private ruleFromLine(line: OrchestratorLine): string | null {
    const code = line.code.trim();
    const quotedValues = this.extractQuotedValues(code);
    const explanation = this.shorten(line.explanation, 135);

    if (code.startsWith('workflow.connect') && quotedValues.length >= 2) {
      return `${quotedValues[0]} -> ${quotedValues[1]}: ${explanation}`;
    }

    if (code.startsWith('workflow.add_step') && quotedValues[0]) {
      return `main_agent -> ${quotedValues[0]}: ${explanation}`;
    }

    if (code.startsWith('workflow.configure') && quotedValues[0]) {
      const configuredRule = quotedValues[1] ?? this.extractRuleArgument(code) ?? explanation;
      return `${quotedValues[0]}: ${this.shorten(configuredRule, 135)}`;
    }

    if (code.includes('validation')) {
      return `validation -> main_agent: ${explanation}`;
    }

    return explanation ? `${this.shorten(code, 42)}: ${explanation}` : null;
  }

  private buildPythonStarter(orchestrator: OrchestratorResult, stepNames: string[], rules: string[]): string {
    const activeAnalysis = this.analysisService.activeAnalysis();
    const repoName = this.safeDisplayName(this.repoNameFromUrl(activeAnalysis?.repoUrl) ?? 'analyzed-repository');
    const branch = this.safeDisplayName(activeAnalysis?.branch ?? 'main');
    const stepConfig = this.buildStepConfig(orchestrator, stepNames);
    const handoffs = this.buildHandoffs(orchestrator);
    const validationRules = this.buildValidationRules(orchestrator, rules);

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


STEP_CONFIG: list[dict[str, Any]] = ${this.pythonLiteral(stepConfig)}

HANDOFFS: list[dict[str, str]] = ${this.pythonLiteral(handoffs)}

VALIDATION_RULES: list[str] = ${this.pythonLiteral(validationRules)}


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

  private buildStepConfig(orchestrator: OrchestratorResult, stepNames: string[]): Array<Record<string, unknown>> {
    const activeAgents = this.analysisService.activeAnalysis()?.agents ?? [];
    const agentsBySafeName = new Map(activeAgents.map((agent) => [this.safePythonName(agent.name), agent]));

    return stepNames.map((name) => {
      const agent = agentsBySafeName.get(name);
      const fallbackLine = orchestrator.lines.find((line) => line.code.includes(`"${name}"`));
      const role = agent?.role ?? fallbackLine?.explanation ?? 'Generated workflow step from Hive analysis';
      const signal = agent?.signal ?? fallbackLine?.source?.detail ?? 'Generated from orchestrator pseudocode';

      return {
        name,
        role: this.shorten(role, 160),
        signal: this.shorten(signal, 180),
        command: ['python', '-c', `print('${name} completed')`],
      };
    });
  }

  private buildHandoffs(orchestrator: OrchestratorResult): Array<Record<string, string>> {
    const connectHandoffs = orchestrator.lines
      .filter((line) => line.code.trim().startsWith('workflow.connect'))
      .map((line) => {
        const quotedValues = this.extractQuotedValues(line.code);
        return {
          from: quotedValues[0] ?? 'source_step',
          to: quotedValues[1] ?? 'target_step',
          automation: this.shorten(line.explanation, 190),
        };
      });

    if (connectHandoffs.length) {
      return connectHandoffs.slice(0, 6);
    }

    const activeManualSteps = this.analysisService.activeAnalysis()?.manualSteps ?? [];
    if (activeManualSteps.length) {
      return activeManualSteps.slice(0, 6).map((step) => ({
        from: this.safePythonName(step.from),
        to: this.safePythonName(step.to),
        automation: this.shorten(step.automation || step.issue, 190),
      }));
    }

    return [
      {
        from: 'main_agent',
        to: 'detected_steps',
        automation: 'Run generated steps in order, then validate output and write a machine-readable report.',
      },
    ];
  }

  private buildValidationRules(orchestrator: OrchestratorResult, rules: string[]): string[] {
    const configuredRules = orchestrator.lines
      .filter((line) => line.code.trim().startsWith('workflow.configure') || line.code.includes('validation'))
      .map((line) => this.ruleFromLine(line))
      .filter((rule): rule is string => Boolean(rule));

    const combined = [...configuredRules, ...rules, ...orchestrator.integrationSteps];
    const unique = Array.from(new Set(combined.map((rule) => this.shorten(rule, 170))));
    return unique.length ? unique.slice(0, 6) : ['Run every configured step successfully before marking the workflow complete.'];
  }

  private pythonLiteral(value: unknown): string {
    return JSON.stringify(value, null, 4)
      .replace(/\btrue\b/g, 'True')
      .replace(/\bfalse\b/g, 'False')
      .replace(/\bnull\b/g, 'None');
  }

  private repoNameFromUrl(repoUrl: string | undefined): string | null {
    if (!repoUrl) {
      return null;
    }

    const cleanUrl = repoUrl.replace(/\.git$/i, '');
    const parts = cleanUrl.split(/[\\/]/).filter(Boolean);
    return parts.at(-1) ?? null;
  }

  private safeDisplayName(value: string): string {
    return value.replace(/\r?\n/g, ' ').replace(/"""/g, "'''").trim();
  }

  private extractQuotedValues(value: string): string[] {
    const matches = value.matchAll(/"([^"]*)"/g);
    return Array.from(matches, (match) => match[1]);
  }

  private extractRuleArgument(value: string): string | null {
    const match = value.match(/rule="([^"]*)"/);
    return match?.[1] ?? null;
  }

  private safePythonName(value: string): string {
    const normalized = value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '');

    if (!normalized) {
      return 'step';
    }

    return /^\d/.test(normalized) ? `step_${normalized}` : normalized;
  }

  private shorten(value: string, maxLength: number): string {
    const compact = value.replace(/\s+/g, ' ').trim();
    return compact.length <= maxLength ? compact : `${compact.slice(0, maxLength - 3)}...`;
  }
}
