import * as vscode from 'vscode';
import { BackendClient } from './backendClient';
import { detectGitInfo } from './gitInfo';
import { ExtensionToWebviewMessage, WebviewToExtensionMessage, WorkflowAnswer } from './messageTypes';
import { buildOrchestratorMarkdown } from './markdown';
import { HiveStateStore } from './state';

export class HiveWebviewPanel {
  private panel?: vscode.WebviewPanel;

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly store: HiveStateStore,
    private readonly backend: BackendClient
  ) {
    store.onDidChange((state) => this.post({ type: 'state', payload: state }));
  }

  async show(): Promise<void> {
    if (this.panel) {
      this.panel.reveal(vscode.ViewColumn.Beside);
      await this.refreshWorkspaceInfo();
      return;
    }

    this.panel = vscode.window.createWebviewPanel(
      'hiveAnalysis',
      'Hive Analysis',
      vscode.ViewColumn.Beside,
      {
        enableScripts: true,
        retainContextWhenHidden: true
      }
    );

    this.panel.webview.html = this.getHtml(this.panel.webview);
    this.panel.onDidDispose(() => {
      this.panel = undefined;
    });
    this.panel.webview.onDidReceiveMessage((message: WebviewToExtensionMessage) => {
      void this.handleMessage(message);
    });

    await this.refreshWorkspaceInfo();
    this.post({ type: 'state', payload: this.store.get() });
  }

  async analyzeCurrentRepository(): Promise<void> {
    await this.show();
    const info = await this.refreshWorkspaceInfo();
    if (!info.repoUrl) {
      throw new Error('No GitHub remote found for the current workspace. Configure origin or enter the repo URL manually in the panel.');
    }
    await this.runAnalyze({ repoUrl: info.repoUrl, branch: info.branch ?? undefined });
  }

  async generateOrchestrator(): Promise<void> {
    await this.show();
    const sessionId = this.store.get().sessionId;
    if (!sessionId) {
      throw new Error('Run Hive analysis before generating an orchestrator.');
    }
    await this.runGenerateOrchestrator(sessionId);
  }

  private async handleMessage(message: WebviewToExtensionMessage): Promise<void> {
    try {
      if (message.type === 'ready') {
        await this.refreshWorkspaceInfo();
        this.post({ type: 'state', payload: this.store.get() });
      }

      if (message.type === 'analyzeRepo') {
        const info = await this.refreshWorkspaceInfo();
        const repoUrl = message.payload.repoUrl || info.repoUrl;
        if (!repoUrl) {
          throw new Error('Repository URL is required.');
        }
        await this.runAnalyze({
          repoUrl,
          branch: message.payload.branch || info.branch || undefined,
          token: message.payload.token
        });
      }

      if (message.type === 'submitAnswers') {
        await this.runSubmitAnswers(message.payload.sessionId, message.payload.answers);
      }

      if (message.type === 'generateOrchestrator') {
        await this.runGenerateOrchestrator(message.payload.sessionId);
      }

      if (message.type === 'saveMarkdown') {
        await saveMarkdown(this.store, message.payload.fileName, message.payload.content);
      }
    } catch (error) {
      this.post({ type: 'error', payload: { message: errorMessage(error) } });
      await this.store.update({ orchestratorStatus: 'error' });
      void vscode.window.showErrorMessage(`Hive: ${errorMessage(error)}`);
    }
  }

  private async refreshWorkspaceInfo() {
    const info = await detectGitInfo();
    await this.store.setWorkspaceInfo(info.workspaceRoot, info.repoUrl, info.branch);
    this.post({
      type: 'workspaceInfo',
      payload: {
        repoUrl: info.repoUrl,
        branch: info.branch,
        workspaceName: info.workspaceName
      }
    });
    return info;
  }

  private async runAnalyze(payload: { repoUrl: string; branch?: string; token?: string }): Promise<void> {
    this.post({ type: 'loading', payload: { operation: 'analysis', active: true } });
    const online = await this.backend.checkHealth();
    await this.store.update({ backendStatus: online ? 'online' : 'offline' });
    if (!online) {
      throw new Error(`Backend unavailable at ${this.backend.baseUrl}. Start it with mvn spring-boot:run and retry.`);
    }

    const result = await this.backend.analyzeRepository(payload);
    await this.store.setAnalysis(result);
    this.post({ type: 'analysisResult', payload: result });
    this.post({ type: 'loading', payload: { operation: 'analysis', active: false } });
  }

  private async runSubmitAnswers(sessionId: string, answers: WorkflowAnswer[]): Promise<void> {
    this.post({ type: 'loading', payload: { operation: 'answers', active: true } });
    const profile = await this.backend.submitAnswers(sessionId, answers);
    await this.store.setProfile(profile);
    this.post({ type: 'profileResult', payload: profile });
    this.post({ type: 'loading', payload: { operation: 'answers', active: false } });
  }

  private async runGenerateOrchestrator(sessionId: string): Promise<void> {
    this.post({ type: 'loading', payload: { operation: 'orchestrator', active: true } });
    const orchestrator = await this.backend.generateOrchestrator(sessionId);
    await this.store.setOrchestrator(orchestrator);
    this.post({ type: 'orchestratorResult', payload: orchestrator });
    this.post({ type: 'loading', payload: { operation: 'orchestrator', active: false } });
  }

  private post(message: ExtensionToWebviewMessage): void {
    void this.panel?.webview.postMessage(message);
  }

  private getHtml(webview: vscode.Webview): string {
    const nonce = getNonce();
    return `<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
  <title>Hive Analysis</title>
  <style>
    :root { color-scheme: light dark; }
    body { margin: 0; color: var(--vscode-foreground); background: var(--vscode-editor-background); font-family: var(--vscode-font-family); }
    main { max-width: 1120px; margin: 0 auto; padding: 18px; }
    header { display: flex; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--vscode-panel-border); padding-bottom: 14px; }
    h1, h2, h3 { margin: 0; font-weight: 650; letter-spacing: 0; }
    h1 { font-size: 22px; }
    h2 { font-size: 16px; margin-bottom: 10px; }
    h3 { font-size: 14px; margin-bottom: 8px; }
    .muted { color: var(--vscode-descriptionForeground); }
    .grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin: 16px 0; }
    .panel { border: 1px solid var(--vscode-panel-border); border-radius: 6px; padding: 14px; background: var(--vscode-sideBar-background); }
    .wide { grid-column: 1 / -1; }
    label { display: block; font-size: 12px; margin-bottom: 4px; color: var(--vscode-descriptionForeground); }
    input, textarea { width: 100%; box-sizing: border-box; color: var(--vscode-input-foreground); background: var(--vscode-input-background); border: 1px solid var(--vscode-input-border, transparent); padding: 8px; border-radius: 3px; font: inherit; }
    textarea { min-height: 74px; resize: vertical; }
    button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: 0; padding: 8px 11px; border-radius: 3px; cursor: pointer; font: inherit; }
    button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
    button:disabled { opacity: .55; cursor: default; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    .stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
    .stat { border-left: 3px solid var(--vscode-focusBorder); padding-left: 8px; min-height: 42px; }
    .stat strong { display: block; font-size: 18px; }
    ul { margin: 0; padding-left: 18px; }
    li { margin: 6px 0; }
    pre { overflow: auto; background: var(--vscode-textCodeBlock-background); padding: 12px; border-radius: 4px; }
    code { font-family: var(--vscode-editor-font-family); }
    .question { border-top: 1px solid var(--vscode-panel-border); padding-top: 12px; margin-top: 12px; }
    .option { display: block; margin: 6px 0; }
    .error { color: var(--vscode-errorForeground); }
    @media (max-width: 760px) { .grid, .stats { grid-template-columns: 1fr; } header { align-items: flex-start; flex-direction: column; } }
  </style>
</head>
<body>
  <main>
    <header>
      <div>
        <h1>Hive Orchestrator</h1>
        <div id="workspace" class="muted">Detecting workspace...</div>
      </div>
      <div class="actions">
        <button id="analyzeBtn">Analyze</button>
        <button id="generateBtn" class="secondary">Generate Orchestrator</button>
        <button id="saveBtn" class="secondary">Save Markdown</button>
      </div>
    </header>

    <section class="grid">
      <div class="panel wide">
        <h2>Repository</h2>
        <div class="grid">
          <div>
            <label for="repoUrl">Repository URL</label>
            <input id="repoUrl" placeholder="https://github.com/org/repo">
          </div>
          <div>
            <label for="branch">Branch</label>
            <input id="branch" placeholder="main">
          </div>
          <div>
            <label for="token">Token</label>
            <input id="token" type="password" placeholder="Optional for private repos">
          </div>
        </div>
        <div id="status" class="muted">Idle</div>
        <div id="error" class="error"></div>
      </div>

      <div class="panel wide">
        <h2>Analysis State</h2>
        <div class="stats">
          <div class="stat"><strong id="agentsCount">0</strong><span>agents</span></div>
          <div class="stat"><strong id="unknownCount">0</strong><span>unknowns</span></div>
          <div class="stat"><strong id="score">-</strong><span>score</span></div>
          <div class="stat"><strong id="orchestratorStatus">not-generated</strong><span>orchestrator</span></div>
        </div>
      </div>

      <div class="panel">
        <h2>Detected Agents</h2>
        <ul id="agents"></ul>
      </div>
      <div class="panel">
        <h2>Manual Steps</h2>
        <ul id="manualSteps"></ul>
      </div>
      <div class="panel">
        <h2>Profile</h2>
        <div id="profile" class="muted">No answers submitted yet.</div>
      </div>

      <div class="panel wide">
        <h2>Questions</h2>
        <form id="questions"></form>
        <div class="actions" style="margin-top: 12px;">
          <button id="submitAnswersBtn" type="button" class="secondary">Submit Answers</button>
        </div>
      </div>

      <div class="panel wide">
        <h2>Generated Orchestrator</h2>
        <pre><code id="orchestrator">// Run analysis, answer questions, then generate the orchestrator.</code></pre>
        <h3>Integration Steps</h3>
        <ul id="integrationSteps"></ul>
      </div>
    </section>
  </main>
  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();
    const state = { analysis: null, profile: null, orchestrator: null, extensionState: null, workspace: null };
    const byId = (id) => document.getElementById(id);

    byId('analyzeBtn').addEventListener('click', () => {
      setError('');
      vscode.postMessage({ type: 'analyzeRepo', payload: {
        repoUrl: byId('repoUrl').value.trim(),
        branch: byId('branch').value.trim(),
        token: byId('token').value.trim()
      }});
    });
    byId('submitAnswersBtn').addEventListener('click', () => {
      if (!state.analysis) return;
      const answers = (state.analysis.questions || []).map((q) => ({
        questionId: q.id,
        question: q.text,
        answer: (byId('answer-' + q.id)?.value || '').trim()
      })).filter((answer) => answer.answer);
      vscode.postMessage({ type: 'submitAnswers', payload: { sessionId: state.analysis.sessionId, answers } });
    });
    byId('generateBtn').addEventListener('click', () => {
      if (!state.analysis) return;
      vscode.postMessage({ type: 'generateOrchestrator', payload: { sessionId: state.analysis.sessionId } });
    });
    byId('saveBtn').addEventListener('click', () => {
      vscode.postMessage({ type: 'saveMarkdown', payload: {} });
    });

    window.addEventListener('message', (event) => {
      const message = event.data;
      if (message.type === 'workspaceInfo') {
        state.workspace = message.payload;
        byId('workspace').textContent = (message.payload.workspaceName || 'Workspace') + ' · ' + (message.payload.branch || 'unknown branch');
        if (message.payload.repoUrl && !byId('repoUrl').value) byId('repoUrl').value = message.payload.repoUrl;
        if (message.payload.branch && !byId('branch').value) byId('branch').value = message.payload.branch;
      }
      if (message.type === 'state') {
        state.extensionState = message.payload;
        renderState();
      }
      if (message.type === 'loading') {
        byId('status').textContent = message.payload.active ? 'Running ' + message.payload.operation + '...' : 'Idle';
      }
      if (message.type === 'analysisResult') {
        state.analysis = message.payload;
        renderAnalysis();
      }
      if (message.type === 'profileResult') {
        state.profile = message.payload;
        renderProfile();
      }
      if (message.type === 'orchestratorResult') {
        state.orchestrator = message.payload;
        renderOrchestrator();
      }
      if (message.type === 'error') {
        setError(message.payload.message);
      }
    });

    function renderState() {
      const s = state.extensionState;
      byId('orchestratorStatus').textContent = s.orchestratorStatus;
      byId('unknownCount').textContent = String(s.unknownCount || 0);
      if (!state.analysis && s.analysis) {
        state.analysis = s.analysis;
        renderAnalysis();
      }
      if (!state.profile && s.profile) {
        state.profile = s.profile;
        renderProfile();
      }
      if (!state.orchestrator && s.orchestrator) {
        state.orchestrator = s.orchestrator;
        renderOrchestrator();
      }
    }

    function renderAnalysis() {
      const analysis = state.analysis;
      byId('agentsCount').textContent = String(analysis.agentsDetected || (analysis.agents || []).length || 0);
      byId('unknownCount').textContent = String((analysis.questions || []).length);
      byId('score').textContent = String(analysis.score ?? '-');
      byId('agents').innerHTML = list((analysis.agents || []).map((a) => '<strong>' + esc(a.name) + '</strong><br><span class="muted">' + esc(a.role || a.signal || '') + '</span>'));
      byId('manualSteps').innerHTML = list((analysis.manualSteps || []).map((s) => esc((s.from || 'Developer') + ' -> ' + (s.to || 'workflow') + ': ' + (s.issue || s.automation || 'manual handoff'))));
      byId('questions').innerHTML = (analysis.questions || []).map(renderQuestion).join('') || '<p class="muted">No questions returned.</p>';
      byId('repoUrl').value = analysis.repoUrl || byId('repoUrl').value;
      byId('branch').value = analysis.branch || byId('branch').value;
    }

    function renderQuestion(q) {
      const options = (q.options || []).map((option) => '<option value="' + esc(option) + '">' + esc(option) + '</option>').join('');
      return '<div class="question"><label for="answer-' + esc(q.id) + '">' + esc(q.text) + '</label>' +
        (options ? '<select id="answer-' + esc(q.id) + '">' + options + '</select>' : '<textarea id="answer-' + esc(q.id) + '"></textarea>') +
        (q.whyAsked ? '<p class="muted">' + esc(q.whyAsked) + '</p>' : '') + '</div>';
    }

    function renderProfile() {
      const p = state.profile;
      byId('profile').innerHTML = '<strong>' + esc(p.type) + '</strong><p>' + esc(p.pain || '') + '</p><ul>' + list(p.needs || []) + '</ul>';
    }

    function renderOrchestrator() {
      const o = state.orchestrator;
      byId('orchestrator').textContent = (o.lines || []).map((line) => line.lineNumber + '. ' + line.code + '\\n   ' + line.explanation).join('\\n\\n');
      byId('integrationSteps').innerHTML = list(o.integrationSteps || []);
    }

    function list(items) {
      return items.length ? items.map((item) => '<li>' + item + '</li>').join('') : '<li class="muted">Nothing yet.</li>';
    }

    function setError(message) {
      byId('error').textContent = message || '';
    }

    function esc(value) {
      return String(value ?? '').replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[ch]));
    }

    vscode.postMessage({ type: 'ready' });
  </script>
</body>
</html>`;
  }
}

export async function saveMarkdown(store: HiveStateStore, fileName?: string, explicitContent?: string): Promise<void> {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) {
    throw new Error('Open a workspace before saving the orchestrator.');
  }

  const configuredName = vscode.workspace.getConfiguration('hive').get<string>('saveGeneratedFileName', 'hive-orchestrator.generated.md');
  const target = vscode.Uri.joinPath(root, fileName || configuredName);
  const markdown = buildOrchestratorMarkdown(store.get(), explicitContent);
  await vscode.workspace.fs.writeFile(target, Buffer.from(markdown, 'utf8'));
  void vscode.window.showInformationMessage(`Hive orchestrator saved to ${target.fsPath}`);
}

function getNonce(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let value = '';
  for (let i = 0; i < 32; i++) {
    value += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return value;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
