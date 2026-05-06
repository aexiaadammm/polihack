import * as vscode from 'vscode';
import { HiveStateStore } from './state';

export class HiveStateViewProvider implements vscode.WebviewViewProvider {
  private view?: vscode.WebviewView;

  constructor(private readonly store: HiveStateStore) {
    store.onDidChange(() => this.render());
  }

  resolveWebviewView(webviewView: vscode.WebviewView): void {
    this.view = webviewView;
    webviewView.webview.options = { enableScripts: true };
    webviewView.webview.onDidReceiveMessage((message: { type?: string }) => {
      if (message.type === 'openPanel') {
        void vscode.commands.executeCommand('hive.openAnalysisPanel');
      }
      if (message.type === 'analyze') {
        void vscode.commands.executeCommand('hive.analyzeCurrentRepository');
      }
    });
    this.render();
  }

  private render(): void {
    if (!this.view) {
      return;
    }

    const state = this.store.get();
    const agents = state.detectedAgents.map((agent) => `<li><strong>${escapeHtml(agent.name)}</strong><span>${escapeHtml(agent.role)}</span></li>`).join('');
    this.view.webview.html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
  <style>
    body { color: var(--vscode-foreground); font-family: var(--vscode-font-family); padding: 12px; }
    .metric { margin: 0 0 10px; }
    .muted { color: var(--vscode-descriptionForeground); }
    button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: 0; padding: 7px 10px; border-radius: 3px; cursor: pointer; margin-right: 6px; }
    ul { padding-left: 18px; }
    li { margin-bottom: 8px; }
    li span { display: block; color: var(--vscode-descriptionForeground); font-size: 12px; }
    code { color: var(--vscode-textPreformat-foreground); }
  </style>
</head>
<body>
  <p class="metric"><strong>Hive:</strong> ${state.detectedAgents.length} agents detected</p>
  <p class="metric">${state.unknownCount} unknown orchestration rules</p>
  <p class="metric">Orchestrator status: <code>${state.orchestratorStatus}</code></p>
  <p class="metric muted">${escapeHtml(state.repoUrl ?? 'No repository detected')}</p>
  <p class="metric muted">Branch: ${escapeHtml(state.branch ?? 'unknown')}</p>
  <button onclick="vscode.postMessage({ type: 'analyze' })">Analyze</button>
  <button onclick="vscode.postMessage({ type: 'openPanel' })">Open Panel</button>
  <h4>Detected Agents</h4>
  <ul>${agents || '<li class="muted">No analysis yet.</li>'}</ul>
  <script>
    const vscode = acquireVsCodeApi();
  </script>
</body>
</html>`;
  }
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[char] ?? char));
}
