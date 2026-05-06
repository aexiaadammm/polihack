import * as vscode from 'vscode';
import { HiveCodeLensProvider, looksRelevantForStale } from './agentCodeLens';
import { BackendClient } from './backendClient';
import { detectGitInfo } from './gitInfo';
import { HiveStateViewProvider } from './stateView';
import { HiveStateStore } from './state';
import { HiveStatusBar } from './statusBar';
import { HiveWebviewPanel, saveMarkdown } from './webviewPanel';

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const store = new HiveStateStore(context);
  const backend = new BackendClient();
  const panel = new HiveWebviewPanel(context, store, backend);
  const statusBar = new HiveStatusBar(context);
  const stateView = new HiveStateViewProvider(store);

  statusBar.update(store.get());
  context.subscriptions.push(store.onDidChange((state) => statusBar.update(state)));

  const info = await detectGitInfo();
  await store.setWorkspaceInfo(info.workspaceRoot, info.repoUrl, info.branch);

  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('hive.orchestrationState', stateView),
    vscode.languages.registerCodeLensProvider(getCodeLensSelectors(), new HiveCodeLensProvider()),
    vscode.commands.registerCommand('hive.openAnalysisPanel', () => panel.show()),
    vscode.commands.registerCommand('hive.analyzeCurrentRepository', async () => runCommand(() => panel.analyzeCurrentRepository())),
    vscode.commands.registerCommand('hive.generateOrchestrator', async () => runCommand(() => panel.generateOrchestrator())),
    vscode.commands.registerCommand('hive.saveOrchestratorAsMarkdown', async () => runCommand(() => saveMarkdown(store))),
    vscode.commands.registerCommand('hive.configureBackendUrl', async () => configureBackendUrl()),
    vscode.commands.registerCommand('hive.explainRole', async (_uri?: vscode.Uri, name?: string) => explainRole(name)),
    vscode.commands.registerCommand('hive.addToOrchestrator', async (_uri?: vscode.Uri, name?: string) => addToOrchestrator(store, name)),
    vscode.commands.registerCommand('hive.markAsNotAgent', async (_uri?: vscode.Uri, name?: string) => markAsNotAgent(name)),
    vscode.commands.registerCommand('hive.askAboutComponent', async (_uri?: vscode.Uri, name?: string) => askAboutComponent(name)),
    vscode.workspace.onDidSaveTextDocument(async (document) => {
      const state = store.get();
      if (state.orchestratorStatus !== 'fresh' || !looksRelevantForStale(document)) {
        return;
      }

      await store.markStale(vscode.workspace.asRelativePath(document.uri, false));
      void vscode.window.showInformationMessage(
        `Hive: Orchestrator may be stale after changes in ${vscode.workspace.asRelativePath(document.uri, false)}.`,
        'Regenerate',
        'Show Panel',
        'Ignore'
      ).then((choice) => {
        if (choice === 'Regenerate') {
          void vscode.commands.executeCommand('hive.generateOrchestrator');
        }
        if (choice === 'Show Panel') {
          void vscode.commands.executeCommand('hive.openAnalysisPanel');
        }
      });
    })
  );
}

function getCodeLensSelectors(): vscode.DocumentSelector {
  return [
    { scheme: 'file', language: 'typescript' },
    { scheme: 'file', language: 'javascript' },
    { scheme: 'file', language: 'java' },
    { scheme: 'file', language: 'python' },
    { scheme: 'file', language: 'json' },
    { scheme: 'file', language: 'yaml' },
    { scheme: 'file', pattern: '**/*.tsx' },
    { scheme: 'file', pattern: '**/*.jsx' },
    { scheme: 'file', pattern: '**/*.kt' },
    { scheme: 'file', pattern: '**/*.go' },
    { scheme: 'file', pattern: '**/*.yml' }
  ];
}

export function deactivate(): void {
  // No background backend is started by the MVP extension.
}

async function runCommand(action: () => Promise<void>): Promise<void> {
  try {
    await action();
  } catch (error) {
    void vscode.window.showErrorMessage(`Hive: ${error instanceof Error ? error.message : String(error)}`);
  }
}

async function configureBackendUrl(): Promise<void> {
  const current = vscode.workspace.getConfiguration('hive').get<string>('backendUrl', 'http://localhost:8080');
  const next = await vscode.window.showInputBox({
    title: 'Hive Backend URL',
    prompt: 'Set the Spring Boot backend URL.',
    value: current
  });
  if (next) {
    await vscode.workspace.getConfiguration('hive').update('backendUrl', next, vscode.ConfigurationTarget.Workspace);
  }
}

async function explainRole(name?: string): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  const file = editor ? vscode.workspace.asRelativePath(editor.document.uri, false) : 'current file';
  void vscode.window.showInformationMessage(`Hive inference: ${name ?? 'This component'} looks relevant because its name or file path matches agent/workflow signals in ${file}.`);
}

async function addToOrchestrator(store: HiveStateStore, name?: string): Promise<void> {
  const state = store.get();
  const agentName = name ?? 'Selected component';
  const exists = state.detectedAgents.some((agent) => agent.name === agentName);
  if (!exists) {
    await store.update({
      detectedAgents: [
        ...state.detectedAgents,
        {
          name: agentName,
          role: 'Manually marked as an orchestration candidate from VS Code.',
          usage: 'Developer selected this component from CodeLens.',
          signal: 'manual-codelens'
        }
      ],
      orchestratorStatus: state.orchestratorStatus === 'fresh' ? 'stale' : state.orchestratorStatus
    });
  }
  void vscode.window.showInformationMessage(`Hive: ${agentName} added as an orchestrator candidate.`);
}

async function markAsNotAgent(name?: string): Promise<void> {
  void vscode.window.showInformationMessage(`Hive: ${name ?? 'This component'} will be treated as not-agent for this session.`);
}

async function askAboutComponent(name?: string): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  const file = editor ? vscode.workspace.asRelativePath(editor.document.uri, false) : 'current file';
  void vscode.window.showInformationMessage(`Hive: ${name ?? 'This component'} in ${file} can be reviewed from the Analysis Panel.`);
  await vscode.commands.executeCommand('hive.openAnalysisPanel');
}
