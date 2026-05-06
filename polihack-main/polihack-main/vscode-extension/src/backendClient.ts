import * as vscode from 'vscode';
import { AnalysisSummary, OrchestratorResult, UserProfile, WorkflowAnswer } from './messageTypes';

export class BackendClient {
  get baseUrl(): string {
    return vscode.workspace.getConfiguration('hive').get<string>('backendUrl', 'http://localhost:8080').replace(/\/$/, '');
  }

  async checkHealth(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/analysis/not-a-real-session`, { method: 'GET' });
      return response.status === 404 || response.ok;
    } catch {
      return false;
    }
  }

  async analyzeRepository(payload: { repoUrl: string; branch?: string; token?: string }): Promise<AnalysisSummary> {
    return this.post<AnalysisSummary>('/analyze-repo', {
      repoUrl: payload.repoUrl,
      branch: payload.branch,
      token: payload.token ?? ''
    });
  }

  async submitAnswers(sessionId: string, answers: WorkflowAnswer[]): Promise<UserProfile> {
    return this.post<UserProfile>('/submit-answers', { sessionId, answers });
  }

  async generateOrchestrator(sessionId: string): Promise<OrchestratorResult> {
    return this.post<OrchestratorResult>('/generate-orchestrator', { sessionId });
  }

  async explainLine(sessionId: string, lineNumber: number, line: string): Promise<{ explanation: string }> {
    return this.post<{ explanation: string }>('/explain-line', { sessionId, lineNumber, line });
  }

  private async post<T>(path: string, body: unknown): Promise<T> {
    let response: Response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
    } catch (error) {
      throw new Error(`Backend unavailable at ${this.baseUrl}. Start the Spring Boot backend and try again. ${errorMessage(error)}`);
    }

    const text = await response.text();
    const data = text ? safeJson(text) : {};
    if (!response.ok) {
      const message = typeof data.error === 'string' ? data.error : text || response.statusText;
      throw new Error(message);
    }
    return data as T;
  }
}

function safeJson(text: string): Record<string, unknown> {
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return { raw: text };
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
