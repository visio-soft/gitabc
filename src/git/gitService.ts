import * as vscode from 'vscode';
import { exec } from 'child_process';
import { promisify } from 'util';
import * as path from 'path';
import * as fs from 'fs';

const execAsync = promisify(exec);

export interface Branch {
    name: string;
    isCurrent: boolean;
    isRemote: boolean;
}

export interface FileChange {
    path: string;
    status: FileStatus;
    staged: boolean;
}

export enum FileStatus {
    Modified = 'M',
    Added = 'A',
    Deleted = 'D',
    Untracked = '?',
    Renamed = 'R',
    Copied = 'C',
    Conflicting = 'U'
}

export interface CommitStatus {
    ahead: number;
    behind: number;
}

export class GitService {
    private async execGit(repoPath: string, args: string): Promise<string> {
        try {
            const { stdout } = await execAsync(`git ${args}`, { cwd: repoPath });
            return stdout.trim();
        } catch (error: any) {
            throw new Error(error.stderr || error.message);
        }
    }

    async isGitRepository(repoPath: string): Promise<boolean> {
        try {
            await execAsync('git rev-parse --git-dir', { cwd: repoPath });
            return true;
        } catch {
            return false;
        }
    }

    async getRemoteUrl(repoPath: string): Promise<string | null> {
        try {
            const url = await this.execGit(repoPath, 'config --get remote.origin.url');
            return url || null;
        } catch {
            return null;
        }
    }

    async getCurrentBranch(repoPath: string): Promise<string> {
        try {
            return await this.execGit(repoPath, 'rev-parse --abbrev-ref HEAD');
        } catch {
            return 'HEAD';
        }
    }

    async getBranches(repoPath: string): Promise<Branch[]> {
        try {
            const output = await this.execGit(repoPath, 'branch --format="%(refname:short)|%(HEAD)"');
            const currentBranch = await this.getCurrentBranch(repoPath);

            return output.split('\n')
                .filter(line => line.trim())
                .map(line => {
                    const [name] = line.split('|');
                    return {
                        name: name.trim(),
                        isCurrent: name.trim() === currentBranch,
                        isRemote: false
                    };
                });
        } catch {
            return [];
        }
    }

    async getCommitStatus(repoPath: string): Promise<CommitStatus> {
        try {
            const output = await this.execGit(repoPath, 'rev-list --left-right --count HEAD...@{upstream}');
            const [ahead, behind] = output.split('\t').map(n => parseInt(n, 10) || 0);
            return { ahead, behind };
        } catch {
            return { ahead: 0, behind: 0 };
        }
    }

    async getFileChanges(repoPath: string): Promise<FileChange[]> {
        const changes: FileChange[] = [];

        try {
            // Get staged changes
            const stagedOutput = await this.execGit(repoPath, 'diff --cached --name-status');
            for (const line of stagedOutput.split('\n').filter(l => l.trim())) {
                const [status, ...pathParts] = line.split('\t');
                const filePath = pathParts.join('\t');
                if (filePath) {
                    changes.push({
                        path: filePath,
                        status: this.parseStatus(status),
                        staged: true
                    });
                }
            }

            // Get unstaged changes (modified tracked files)
            const unstagedOutput = await this.execGit(repoPath, 'diff --name-status');
            for (const line of unstagedOutput.split('\n').filter(l => l.trim())) {
                const [status, ...pathParts] = line.split('\t');
                const filePath = pathParts.join('\t');
                if (filePath && !changes.some(c => c.path === filePath && !c.staged)) {
                    changes.push({
                        path: filePath,
                        status: this.parseStatus(status),
                        staged: false
                    });
                }
            }

            // Get untracked files
            const untrackedOutput = await this.execGit(repoPath, 'ls-files --others --exclude-standard');
            for (const filePath of untrackedOutput.split('\n').filter(l => l.trim())) {
                changes.push({
                    path: filePath,
                    status: FileStatus.Untracked,
                    staged: false
                });
            }
        } catch (error) {
            console.error('Error getting file changes:', error);
        }

        return changes;
    }

    private parseStatus(status: string): FileStatus {
        const firstChar = status.charAt(0).toUpperCase();
        switch (firstChar) {
            case 'M': return FileStatus.Modified;
            case 'A': return FileStatus.Added;
            case 'D': return FileStatus.Deleted;
            case 'R': return FileStatus.Renamed;
            case 'C': return FileStatus.Copied;
            case 'U': return FileStatus.Conflicting;
            default: return FileStatus.Modified;
        }
    }

    async stage(repoPath: string, filePath: string): Promise<void> {
        const relativePath = path.relative(repoPath, filePath);
        await this.execGit(repoPath, `add "${relativePath}"`);
    }

    async unstage(repoPath: string, filePath: string): Promise<void> {
        const relativePath = path.relative(repoPath, filePath);
        await this.execGit(repoPath, `reset HEAD "${relativePath}"`);
    }

    async stageAll(repoPath: string): Promise<void> {
        await this.execGit(repoPath, 'add -A');
    }

    async unstageAll(repoPath: string): Promise<void> {
        await this.execGit(repoPath, 'reset HEAD');
    }

    async discardChanges(repoPath: string, filePath: string): Promise<void> {
        const relativePath = path.relative(repoPath, filePath);
        try {
            // For tracked files
            await this.execGit(repoPath, `checkout -- "${relativePath}"`);
        } catch {
            // For untracked files
            const fullPath = path.join(repoPath, relativePath);
            if (fs.existsSync(fullPath)) {
                fs.unlinkSync(fullPath);
            }
        }
    }

    async commit(repoPath: string, message: string): Promise<void> {
        // First stage all changes
        await this.stageAll(repoPath);
        await this.execGit(repoPath, `commit -m "${message.replace(/"/g, '\\"')}"`);
    }

    async commitStaged(repoPath: string, message: string): Promise<void> {
        await this.execGit(repoPath, `commit -m "${message.replace(/"/g, '\\"')}"`);
    }

    async commitAmend(repoPath: string, message?: string): Promise<void> {
        if (message) {
            await this.execGit(repoPath, `commit --amend -m "${message.replace(/"/g, '\\"')}"`);
        } else {
            await this.execGit(repoPath, 'commit --amend --no-edit');
        }
    }

    async push(repoPath: string): Promise<void> {
        await this.execGit(repoPath, 'push');
    }

    async pull(repoPath: string): Promise<void> {
        await this.execGit(repoPath, 'pull');
    }

    async fetch(repoPath: string): Promise<void> {
        await this.execGit(repoPath, 'fetch');
    }

    async checkout(repoPath: string, branchName: string): Promise<void> {
        await this.execGit(repoPath, `checkout "${branchName}"`);
    }

    async createBranch(repoPath: string, branchName: string): Promise<void> {
        await this.execGit(repoPath, `branch "${branchName}"`);
    }

    async clone(url: string, destination: string): Promise<void> {
        await execAsync(`git clone "${url}"`, { cwd: destination });
    }

    getOriginalUri(uri: vscode.Uri): vscode.Uri {
        return uri.with({ scheme: 'git', query: 'HEAD' });
    }
}
