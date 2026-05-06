import * as vscode from 'vscode';
import { HiveExtensionState } from './messageTypes';

export class HiveStatusBar {
  private readonly item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);

  constructor(context: vscode.ExtensionContext) {
    this.item.command = 'hive.openAnalysisPanel';
    this.item.tooltip = 'Open Hive analysis panel';
    context.subscriptions.push(this.item);
  }

  update(state: HiveExtensionState, loading = false): void {
    if (loading) {
      this.item.text = '$(sync~spin) Hive: analyzing...';
    } else if (state.backendStatus === 'offline') {
      this.item.text = '$(warning) Hive: backend offline';
    } else if (state.orchestratorStatus === 'stale') {
      this.item.text = '$(warning) Hive: orchestrator stale';
    } else if (state.detectedAgents.length > 0 || state.unknownCount > 0) {
      this.item.text = `$(beaker) Hive: ${state.detectedAgents.length} agents · ${state.unknownCount} unknowns`;
    } else {
      this.item.text = '$(beaker) Hive: idle';
    }
    this.item.show();
  }
}
