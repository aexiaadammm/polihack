import * as vscode from 'vscode';

const candidatePattern = /\b(class|function|const|interface)\s+([A-Za-z0-9_]*(?:Agent|Workflow|Orchestrator|Service)[A-Za-z0-9_]*)/g;

export class HiveCodeLensProvider implements vscode.CodeLensProvider {
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    if (!isRelevantDocument(document)) {
      return [];
    }

    const lenses: vscode.CodeLens[] = [];
    const text = document.getText();
    let match: RegExpExecArray | null;

    while ((match = candidatePattern.exec(text)) && lenses.length < 12) {
      const position = document.positionAt(match.index);
      const range = new vscode.Range(position, position);
      const componentName = match[2];
      lenses.push(
        new vscode.CodeLens(range, {
          title: 'Hive: Agent candidate',
          command: 'hive.askAboutComponent',
          arguments: [document.uri, componentName]
        }),
        new vscode.CodeLens(range, {
          title: 'Explain Role',
          command: 'hive.explainRole',
          arguments: [document.uri, componentName]
        }),
        new vscode.CodeLens(range, {
          title: 'Add to Orchestrator',
          command: 'hive.addToOrchestrator',
          arguments: [document.uri, componentName]
        })
      );
    }

    return lenses;
  }
}

export function isRelevantDocument(document: vscode.TextDocument): boolean {
  if (document.uri.scheme !== 'file') {
    return false;
  }
  return /\.(java|js|ts|tsx|jsx|py|kt|go|ya?ml|json)$/i.test(document.fileName);
}

export function looksRelevantForStale(document: vscode.TextDocument): boolean {
  if (!isRelevantDocument(document)) {
    return false;
  }
  const fileName = document.fileName.toLowerCase();
  const text = document.getText();
  return /agent|workflow|orchestrator|prompt|claude|analysis|service/i.test(fileName)
    || /\b(Agent|Workflow|Orchestrator|Claude|Prompt)\b/.test(text);
}
