import * as vscode from 'vscode';
import { GitService } from '../git/gitService';

export class StatusBarManager implements vscode.Disposable {
    private branchItem: vscode.StatusBarItem;
    private syncItem: vscode.StatusBarItem;
    private disposables: vscode.Disposable[] = [];

    constructor(
        private gitService: GitService,
        private repoPath: string
    ) {
        // Branch indicator
        this.branchItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
        this.branchItem.command = 'gitabc.switchBranch';
        this.branchItem.tooltip = 'Click to switch branch';
        this.branchItem.show();
        this.disposables.push(this.branchItem);

        // Sync status (ahead/behind)
        this.syncItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 99);
        this.syncItem.command = 'gitabc.fetch';
        this.syncItem.tooltip = 'Click to fetch';
        this.disposables.push(this.syncItem);
    }

    async update(): Promise<void> {
        const config = vscode.workspace.getConfiguration('gitabc');
        if (!config.get<boolean>('showStatusBarItem', true)) {
            this.branchItem.hide();
            this.syncItem.hide();
            return;
        }

        try {
            // Update branch
            const branch = await this.gitService.getCurrentBranch(this.repoPath);
            this.branchItem.text = `$(git-branch) ${branch}`;
            this.branchItem.show();

            // Update sync status
            const status = await this.gitService.getCommitStatus(this.repoPath);

            if (status.ahead > 0 || status.behind > 0) {
                const parts: string[] = [];
                if (status.behind > 0) {
                    parts.push(`$(cloud-download) ${status.behind}`);
                }
                if (status.ahead > 0) {
                    parts.push(`$(cloud-upload) ${status.ahead}`);
                }
                this.syncItem.text = parts.join(' ');
                this.syncItem.tooltip = `${status.behind} to pull, ${status.ahead} to push`;
                this.syncItem.show();
            } else {
                this.syncItem.text = '$(check)';
                this.syncItem.tooltip = 'Up to date';
                this.syncItem.show();
            }
        } catch (error) {
            this.branchItem.text = '$(git-branch) unknown';
            this.syncItem.hide();
        }
    }

    dispose() {
        this.disposables.forEach(d => d.dispose());
    }
}
