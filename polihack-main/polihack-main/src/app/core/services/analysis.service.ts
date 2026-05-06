import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

export interface RepositoryPayload {
  repoUrl: string;
  branch?: string;
  token?: string;
}

export interface AgentSummary {
  name: string;
  role: string;
  usage: string;
  signal: string;
}

export interface ManualStep {
  from: string;
  to: string;
  issue: string;
  automation: string;
}

export interface SourceHint {
  type: 'CODE' | 'ANSWER' | 'INFERENCE' | string;
  file?: string;
  questionId?: string;
  detail: string;
}

export interface BackendQuestion {
  id: string;
  text: string;
  type: 'ORDERING' | 'CONDITION' | 'THRESHOLD' | 'VALIDATION' | 'TRIGGER' | 'FREE_TEXT' | string;
  options: string[];
  whyAsked?: string;
  sourceHints?: SourceHint[];
}

export interface AnalysisSummary {
  sessionId: string;
  repoUrl: string;
  branch: string;
  agentsDetected: number;
  workflow: 'linear' | 'partial-orchestration' | string;
  reusability: 'low' | 'medium' | string;
  orchestration: 'missing' | 'partial' | string;
  level: 'L3' | string;
  score: number;
  agents: AgentSummary[];
  manualSteps: ManualStep[];
  questions: BackendQuestion[];
  firstQuestion?: BackendQuestion;
}

export interface WorkflowAnswer {
  questionId: string;
  question: string;
  answer: string;
}

export interface UserProfile {
  type: 'manual-linear' | 'review-heavy' | 'partial-orchestrated' | string;
  pain: string;
  needs: string[];
}

export interface OrchestratorLine {
  lineNumber: number;
  code: string;
  explanation: string;
  source?: SourceHint;
}

export interface AutomationFile {
  fileName: string;
  recommendedPath: string;
  language: string;
  rules: string[];
  content: string;
}

export interface OrchestratorResult {
  targetLevel: 'L4' | string;
  profile: UserProfile;
  lines: OrchestratorLine[];
  integrationSteps: string[];
  impact: {
    coordination: string;
    consistency: string;
    scaling: string;
  };
  automationFile?: AutomationFile;
}

@Injectable({
  providedIn: 'root',
})
export class AnalysisService {
  private readonly baseUrl = 'http://localhost:8080';

  readonly activeAnalysis = signal<AnalysisSummary | null>(null);
  readonly activeAnswers = signal<WorkflowAnswer[]>([]);
  readonly activeProfile = signal<UserProfile | null>(null);
  readonly activeOrchestrator = signal<OrchestratorResult | null>(null);

  constructor(private readonly http: HttpClient) {}

  analyzeRepository(payload: RepositoryPayload): Observable<AnalysisSummary> {
    const request = {
      repoUrl: payload.repoUrl,
      branch: payload.branch,
      token: payload.token ?? '',
    };

    return this.http.post<AnalysisSummary>(`${this.baseUrl}/analyze-repo`, request).pipe(
      tap((summary) => {
        this.activeAnalysis.set(summary);
        this.activeAnswers.set([]);
        this.activeProfile.set(null);
        this.activeOrchestrator.set(null);
      }),
    );
  }

  getAnalysis(sessionId: string): Observable<AnalysisSummary> {
    return this.http.get<AnalysisSummary>(`${this.baseUrl}/analysis/${sessionId}`).pipe(
      tap((summary) => {
        this.activeAnalysis.set(summary);
      }),
    );
  }

  submitAnswers(sessionId: string, answers: WorkflowAnswer[]): Observable<UserProfile> {
    return this.http
      .post<UserProfile>(`${this.baseUrl}/submit-answers`, {
        sessionId,
        answers,
      })
      .pipe(
        tap((profile) => {
          this.activeAnswers.set(answers);
          this.activeProfile.set(profile);
        }),
      );
  }

  generateOrchestrator(sessionId: string): Observable<OrchestratorResult> {
    return this.http
      .post<OrchestratorResult>(`${this.baseUrl}/generate-orchestrator`, { sessionId })
      .pipe(
        tap((orchestrator) => {
          this.activeOrchestrator.set(orchestrator);
        }),
      );
  }

  explainLine(sessionId: string, line: OrchestratorLine): Observable<{ explanation: string }> {
    return this.http.post<{ explanation: string }>(`${this.baseUrl}/explain-line`, {
      sessionId,
      lineNumber: line.lineNumber,
      line: line.code,
    });
  }

  /*
   * Mock fallback intentionally disabled.
   * If one of these calls fails, the UI should show the backend error so the
   * team can test the real integration contract instead of hiding failures.
   */
}
