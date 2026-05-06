# Hive Orchestrator

## Repository

- URL: https://github.com/ciutaniulian/polihack
- Branch: demo
- Session: demo-d1d56262-83f4-4658-ae83-522f75915af4
- Last analyzed: 2026-04-26T11:05:56.468Z

## Detected Agents

- **Code Builder**: Generates implementation changes from tickets. (fallback demo)
- **Test Runner**: Runs validation and test checks. (fallback demo)
- **Review Agent**: Reviews output quality and critical issues. (fallback demo)

## Generated Orchestrator

```text
1. workflow = Orchestrator(name="main_agent")
2. workflow.add_step("code_builder", role="Generates implementation changes from tickets.")
3. workflow.add_step("test_runner", role="Runs validation and test checks.")
4. workflow.add_step("review_agent", role="Reviews output quality and critical issues.")
5. workflow.connect("code_builder", "test_runner", mode="automated")
6. workflow.connect("test_runner", "review_agent", mode="automated")
7. workflow.add_validation("developer_rules_required")
```

- Line 1: Creates the main owner for this repository flow, because the analysis found separate agent-like capabilities without one explicit controller for the whole process.
- Line 2: Adds Code Builder as an orchestrated step because Claude/local analysis associated it with: Generates implementation changes from tickets.. Signal used: fallback demo.
- Line 3: Adds Test Runner as an orchestrated step because Claude/local analysis associated it with: Runs validation and test checks.. Signal used: fallback demo.
- Line 4: Adds Review Agent as an orchestrated step because Claude/local analysis associated it with: Reviews output quality and critical issues.. Signal used: fallback demo.
- Line 5: Automates the handoff code_builder -> test_runner. Manual issue detected: Developer coordinates code -> test manually. Automation rule: Pass generated output directly into validation..
- Line 6: Automates the handoff test_runner -> review_agent. Manual issue detected: Developer decides manually when to continue. Automation rule: Add explicit validation gates in main agent..
- Line 7: Adds a validation gate because the workflow is incomplete until the developer defines what blocks or approves the final output.

## Repo-Ready Automation File

- File: `hive_main_agent.py`
- Recommended path: `.hive/hive_main_agent.py`
- Language: `python`

### Automation Rules

- workflow = Orchestrator(name="main_agent"): Creates the main owner for this repository flow, because the analysis found separate agent-like capabilities without one explicit co...
- main_agent -> code_builder: Adds Code Builder as an orchestrated step because Claude/local analysis associated it with: Generates implementation changes from ti...
- main_agent -> test_runner: Adds Test Runner as an orchestrated step because Claude/local analysis associated it with: Runs validation and test checks.. Signal ...
- main_agent -> review_agent: Adds Review Agent as an orchestrated step because Claude/local analysis associated it with: Reviews output quality and critical issu...

### Generated Python

```python
"""
HIVE generated main agent for polihack
Branch: demo

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


STEP_CONFIG: list[dict[str, Any]] = [
    {
        "name": "code_builder",
        "role": "Generates implementation changes from tickets.",
        "signal": "fallback demo",
        "command": [
            "python",
            "-c",
            "print('code_builder completed')"
        ]
    },
    {
        "name": "test_runner",
        "role": "Runs validation and test checks.",
        "signal": "fallback demo",
        "command": [
            "python",
            "-c",
            "print('test_runner completed')"
        ]
    },
    {
        "name": "review_agent",
        "role": "Reviews output quality and critical issues.",
        "signal": "fallback demo",
        "command": [
            "python",
            "-c",
            "print('review_agent completed')"
        ]
    }
]

HANDOFFS: list[dict[str, str]] = [
    {
        "from": "code_builder",
        "to": "test_runner",
        "automation": "Automates the handoff code_builder -> test_runner. Manual issue detected: Developer coordinates code -> test manually. Automation rule: Pass generated output directly into validation.."
    },
    {
        "from": "test_runner",
        "to": "review_agent",
        "automation": "Automates the handoff test_runner -> review_agent. Manual issue detected: Developer decides manually when to continue. Automation rule: Add explicit validation gates in main agent.."
    }
]

VALIDATION_RULES: list[str] = [
    "main_agent -> test_runner: Adds Test Runner as an orchestrated step because Claude/local analysis associated it with: Runs validation and test checks.. Signal ...",
    "validation -> main_agent: Adds a validation gate because the workflow is incomplete until the developer defines what blocks or approves the final output.",
    "workflow = Orchestrator(name=\"main_agent\"): Creates the main owner for this repository flow, because the analysis found separate agent-like capabilities without one ex...",
    "main_agent -> code_builder: Adds Code Builder as an orchestrated step because Claude/local analysis associated it with: Generates implementation changes from ti...",
    "main_agent -> review_agent: Adds Review Agent as an orchestrated step because Claude/local analysis associated it with: Reviews output quality and critical issu...",
    "Create `main_agent.yml` for `polihack` on branch `demo` and register `Code Builder` as the entry step because it was detected as: Generates implementation changes from..."
]


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
```

## Integration Steps

- Create `main_agent.yml` for `polihack` on branch `demo` and register `Code Builder` as the entry step because it was detected as: Generates implementation changes from tickets..
- Wire handoff 1: `Code Builder` -> `Test Runner`, then encode this automation rule: Pass generated output directly into validation..
- Wire handoff 2: `Test Runner` -> `Review Agent`, then encode this automation rule: Add explicit validation gates in main agent..
- Before enabling automation, answer and persist this repo-specific missing rule: What is the real order of agents in this workflow?.
- Run a dry run for `polihack`: verify orchestration=`missing`, reuse=`low`, and track the maturity score moving beyond 62/100.