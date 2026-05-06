export type OrchestratorStatus = 'not-generated' | 'fresh' | 'stale' | 'error';

export interface AgentSummary {
  name: string;
  role: string;
  usage: string;
  signal: string;
  file?: string;
}

export interface BackendQuestion {
  id: string;
  text: string;
  type: string;
  options?: string[];
  whyAsked?: string;
}

export interface AnalysisSummary {
  sessionId: string;
  repoUrl: string;
  branch: string;
  agentsDetected: number;
  workflow: string;
  reusability: string;
  orchestration: string;
  level: string;
  score: number;
  agents: AgentSummary[];
  manualSteps: Array<Record<string, unknown>>;
  questions: BackendQuestion[];
  firstQuestion?: BackendQuestion;
}

export interface WorkflowAnswer {
  questionId: string;
  question: string;
  answer: string;
}

export interface UserProfile {
  type: string;
  pain: string;
  needs: string[];
}

export interface OrchestratorLine {
  lineNumber: number;
  code: string;
  explanation: string;
  source?: Record<string, unknown>;
}

export interface AutomationFile {
  fileName: string;
  recommendedPath: string;
  language: string;
  rules: string[];
  content: string;
}

export interface OrchestratorResult {
  targetLevel: string;
  profile: UserProfile;
  lines: OrchestratorLine[];
  integrationSteps: string[];
  impact: Record<string, string>;
  automationFile?: AutomationFile;
}

export interface HiveExtensionState {
  workspaceRoot: string | null;
  repoUrl: string | null;
  branch: string | null;
  sessionId: string | null;
  lastAnalyzedAt: string | null;
  analyzedFiles: string[];
  detectedAgents: AgentSummary[];
  unknownCount: number;
  orchestratorStatus: OrchestratorStatus;
  backendStatus: 'unknown' | 'online' | 'offline';
  analysis?: AnalysisSummary;
  profile?: UserProfile;
  orchestrator?: OrchestratorResult;
}

export type WebviewToExtensionMessage =
  | { type: 'ready' }
  | { type: 'analyzeRepo'; payload: { repoUrl?: string; branch?: string; token?: string } }
  | { type: 'submitAnswers'; payload: { sessionId: string; answers: WorkflowAnswer[] } }
  | { type: 'generateOrchestrator'; payload: { sessionId: string } }
  | { type: 'saveMarkdown'; payload: { fileName?: string; content?: string } };

export type ExtensionToWebviewMessage =
  | { type: 'workspaceInfo'; payload: { repoUrl: string | null; branch: string | null; workspaceName: string | null } }
  | { type: 'state'; payload: HiveExtensionState }
  | { type: 'loading'; payload: { operation: string; active: boolean } }
  | { type: 'analysisResult'; payload: AnalysisSummary }
  | { type: 'profileResult'; payload: UserProfile }
  | { type: 'orchestratorResult'; payload: OrchestratorResult }
  | { type: 'error'; payload: { message: string; details?: string } };
