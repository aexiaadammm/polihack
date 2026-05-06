import * as cp from 'child_process';
import * as path from 'path';
import * as util from 'util';
import * as vscode from 'vscode';

const execFile = util.promisify(cp.execFile);

export interface GitInfo {
  workspaceRoot: string | null;
  workspaceName: string | null;
  repoUrl: string | null;
  branch: string | null;
}

export async function detectGitInfo(): Promise<GitInfo> {
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder) {
    return { workspaceRoot: null, workspaceName: null, repoUrl: null, branch: null };
  }

  const workspaceRoot = folder.uri.fsPath;
  const [remote, branch] = await Promise.all([
    runGit(workspaceRoot, ['config', '--get', 'remote.origin.url']),
    runGit(workspaceRoot, ['branch', '--show-current'])
  ]);

  return {
    workspaceRoot,
    workspaceName: folder.name || path.basename(workspaceRoot),
    repoUrl: normalizeGithubRemote(remote),
    branch: branch || null
  };
}

async function runGit(cwd: string, args: string[]): Promise<string | null> {
  try {
    const { stdout } = await execFile('git', args, { cwd });
    return stdout.trim() || null;
  } catch {
    return null;
  }
}

function normalizeGithubRemote(remote: string | null): string | null {
  if (!remote) {
    return null;
  }

  const clean = remote.trim();
  if (clean.startsWith('https://github.com/')) {
    return clean.replace(/\.git$/, '');
  }

  const sshMatch = clean.match(/^git@github\.com:(.+?)\/(.+?)(?:\.git)?$/);
  if (sshMatch) {
    return `https://github.com/${sshMatch[1]}/${sshMatch[2]}`;
  }

  return clean;
}
