import * as vscode from 'vscode';
import { AnalysisSummary, HiveExtensionState, OrchestratorResult, UserProfile } from './messageTypes';

const stateKey = 'hive.extensionState';

export class HiveStateStore {
  private state: HiveExtensionState = {
    workspaceRoot: null,
    repoUrl: null,
    branch: null,
    sessionId: null,
    lastAnalyzedAt: null,
    analyzedFiles: [],
    detectedAgents: [],
    unknownCount: 0,
    orchestratorStatus: 'not-generated',
    backendStatus: 'unknown'
  };

  private readonly emitter = new vscode.EventEmitter<HiveExtensionState>();
  readonly onDidChange = this.emitter.event;

  constructor(private readonly context: vscode.ExtensionContext) {
    const saved = context.workspaceState.get<HiveExtensionState>(stateKey);
    if (saved) {
      this.state = { ...this.state, ...saved };
    }
  }

  get(): HiveExtensionState {
    return this.state;
  }

  async update(patch: Partial<HiveExtensionState>): Promise<void> {
    this.state = { ...this.state, ...patch };
    await this.context.workspaceState.update(stateKey, this.state);
    this.emitter.fire(this.state);
  }

  async setWorkspaceInfo(workspaceRoot: string | null, repoUrl: string | null, branch: string | null): Promise<void> {
    await this.update({ workspaceRoot, repoUrl, branch });
  }

  async setAnalysis(analysis: AnalysisSummary): Promise<void> {
    await this.update({
      analysis,
      repoUrl: analysis.repoUrl,
      branch: analysis.branch,
      sessionId: analysis.sessionId,
      lastAnalyzedAt: new Date().toISOString(),
      detectedAgents: analysis.agents ?? [],
      unknownCount: analysis.questions?.length ?? 0,
      orchestratorStatus: 'not-generated',
      analyzedFiles: inferAnalyzedFiles(analysis)
    });
  }

  async setProfile(profile: UserProfile): Promise<void> {
    await this.update({ profile });
  }

  async setOrchestrator(orchestrator: OrchestratorResult): Promise<void> {
    await this.update({
      orchestrator,
      orchestratorStatus: 'fresh'
    });
  }

  async markStale(fileName: string): Promise<void> {
    const analyzedFiles = [...new Set([...this.state.analyzedFiles, fileName])];
    await this.update({ orchestratorStatus: 'stale', analyzedFiles });
  }
}

function inferAnalyzedFiles(analysis: AnalysisSummary): string[] {
  const files = new Set<string>();
  for (const agent of analysis.agents ?? []) {
    if (agent.file) {
      files.add(agent.file);
    }
    const signal = agent.signal ?? '';
    const match = signal.match(/([\w./\\-]+\.(?:java|js|ts|tsx|jsx|py|kt|go|ya?ml|json))/i);
    if (match) {
      files.add(match[1].replace(/\\/g, '/'));
    }
  }
  return [...files];
}
