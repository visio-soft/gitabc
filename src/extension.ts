import * as vscode from 'vscode';
import { GitService } from './git/gitService';
import { GitAbcPanelProvider } from './views/gitabcPanel';
import { StatusBarManager } from './ui/statusBar';

let gitService: GitService;
let panelProvider: GitAbcPanelProvider;
let statusBarManager: StatusBarManager;
let autoFetchInterval: NodeJS.Timeout | undefined;

export async function activate(context: vscode.ExtensionContext) {
    console.log('GitABC extension is activating...');

    // Initialize Git service
    gitService = new GitService();

    // Check if we're in a git repository
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
        console.log('No workspace folder found');
        return;
    }

    const repoPath = workspaceFolder.uri.fsPath;
    const isGitRepo = await gitService.isGitRepository(repoPath);
    if (!isGitRepo) {
        console.log('Not a git repository');
        return;
    }

    // Check if remote is GitHub
    const remoteUrl = await gitService.getRemoteUrl(repoPath);
    if (remoteUrl && !remoteUrl.includes('github.com')) {
        vscode.window.showWarningMessage('GitABC only works with GitHub repositories.');
        return;
    }

    // Initialize panel provider
    panelProvider = new GitAbcPanelProvider(context.extensionUri, gitService, repoPath);
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider('gitabcPanel', panelProvider)
    );

    // Initialize status bar
    statusBarManager = new StatusBarManager(gitService, repoPath);
    context.subscriptions.push(statusBarManager);

    // Register commands
    registerCommands(context, repoPath);

    // Setup auto-fetch
    setupAutoFetch(repoPath);

    // Watch for file changes
    const watcher = vscode.workspace.createFileSystemWatcher('**/*');
    watcher.onDidChange(() => panelProvider.refresh());
    watcher.onDidCreate(() => panelProvider.refresh());
    watcher.onDidDelete(() => panelProvider.refresh());
    context.subscriptions.push(watcher);

    console.log('GitABC extension activated successfully');
}

function registerCommands(context: vscode.ExtensionContext, repoPath: string) {
    context.subscriptions.push(
        vscode.commands.registerCommand('gitabc.refresh', async () => {
            await panelProvider.refresh();
            await statusBarManager.update();
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('gitabc.commit', async () => {
            const message = await vscode.window.showInputBox({ placeHolder: 'Commit message' });
            if (message) {
                try {
                    await gitService.commit(repoPath, message);
                    await panelProvider.refresh();
                    await statusBarManager.update();
                    vscode.window.showInformationMessage('Committed successfully');
                } catch (error: any) {
                    vscode.window.showErrorMessage(`Commit failed: ${error.message}`);
                }
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('gitabc.push', async () => {
            try {
                await vscode.window.withProgress(
                    { location: vscode.ProgressLocation.Notification, title: 'Pushing...' },
                    async () => { await gitService.push(repoPath); }
                );
                await statusBarManager.update();
                vscode.window.showInformationMessage('Pushed successfully');
            } catch (error: any) {
                vscode.window.showErrorMessage(`Push failed: ${error.message}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('gitabc.pull', async () => {
            try {
                await vscode.window.withProgress(
                    { location: vscode.ProgressLocation.Notification, title: 'Pulling...' },
                    async () => { await gitService.pull(repoPath); }
                );
                await panelProvider.refresh();
                await statusBarManager.update();
                vscode.window.showInformationMessage('Pulled successfully');
            } catch (error: any) {
                vscode.window.showErrorMessage(`Pull failed: ${error.message}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('gitabc.fetch', async () => {
            try {
                await gitService.fetch(repoPath);
                await statusBarManager.update();
                vscode.window.showInformationMessage('Fetched successfully');
            } catch (error: any) {
                vscode.window.showErrorMessage(`Fetch failed: ${error.message}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('gitabc.switchBranch', async () => {
            const branches = await gitService.getBranches(repoPath);
            const currentBranch = await gitService.getCurrentBranch(repoPath);

            const items = branches.map(b => ({
                label: b.name,
                description: b.name === currentBranch ? '(current)' : ''
            }));

            const selected = await vscode.window.showQuickPick(items, {
                placeHolder: 'Select branch'
            });

            if (selected && selected.label !== currentBranch) {
                try {
                    await gitService.checkout(repoPath, selected.label);
                    await panelProvider.refresh();
                    await statusBarManager.update();
                    vscode.window.showInformationMessage(`Switched to ${selected.label}`);
                } catch (error: any) {
                    vscode.window.showErrorMessage(`Failed: ${error.message}`);
                }
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('gitabc.createBranch', async () => {
            const name = await vscode.window.showInputBox({ placeHolder: 'New branch name' });
            if (name) {
                try {
                    await gitService.createBranch(repoPath, name);
                    await gitService.checkout(repoPath, name);
                    await statusBarManager.update();
                    vscode.window.showInformationMessage(`Created ${name}`);
                } catch (error: any) {
                    vscode.window.showErrorMessage(`Failed: ${error.message}`);
                }
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('gitabc.clone', async () => {
            const url = await vscode.window.showInputBox({
                placeHolder: 'https://github.com/owner/repo.git'
            });

            if (url && url.includes('github.com')) {
                const folders = await vscode.window.showOpenDialog({
                    canSelectFiles: false,
                    canSelectFolders: true,
                    canSelectMany: false
                });

                if (folders?.[0]) {
                    try {
                        await vscode.window.withProgress(
                            { location: vscode.ProgressLocation.Notification, title: 'Cloning...' },
                            async () => { await gitService.clone(url, folders[0].fsPath); }
                        );
                        const repoName = url.split('/').pop()?.replace('.git', '') || 'repo';
                        const clonedPath = vscode.Uri.joinPath(folders[0], repoName);
                        vscode.commands.executeCommand('vscode.openFolder', clonedPath);
                    } catch (error: any) {
                        vscode.window.showErrorMessage(`Clone failed: ${error.message}`);
                    }
                }
            }
        })
    );
}

function setupAutoFetch(repoPath: string) {
    const config = vscode.workspace.getConfiguration('gitabc');
    const autoFetch = config.get<boolean>('autoFetch', true);
    const interval = config.get<number>('autoFetchInterval', 180) * 1000;

    if (autoFetch) {
        autoFetchInterval = setInterval(async () => {
            try {
                await gitService.fetch(repoPath);
                await statusBarManager.update();
            } catch {
                // Silent fail
            }
        }, interval);
    }
}

export function deactivate() {
    if (autoFetchInterval) {
        clearInterval(autoFetchInterval);
    }
}
