import * as vscode from 'vscode';
import { GitService, FileChange, FileStatus } from '../git/gitService';
import * as path from 'path';

interface FileNode {
    type: 'changelist' | 'directory' | 'file';
    name: string;
    path: string;
    status?: string;
    children?: FileNode[];
    checked?: boolean;
}

interface Changelist {
    id: string;
    name: string;
    files: Set<string>;
}

export class GitAbcPanelProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'gitabcPanel';
    private _view?: vscode.WebviewView;
    private fileChanges: FileChange[] = [];
    private selectedFiles: Set<string> = new Set();
    private currentBranch: string = 'main';
    private commitStatus = { ahead: 0, behind: 0 };
    private changelists: Changelist[] = [];
    private fileToChangelist: Map<string, string> = new Map(); // filePath -> changelistId

    constructor(
        private readonly extensionUri: vscode.Uri,
        private gitService: GitService,
        private repoPath: string
    ) {
        // Initialize with default changelist
        this.changelists.push({
            id: 'default',
            name: 'Changes',
            files: new Set()
        });
    }

    public resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken
    ) {
        this._view = webviewView;

        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [this.extensionUri]
        };

        webviewView.webview.html = this.getHtmlContent();

        webviewView.webview.onDidReceiveMessage(async (data) => {
            await this.handleMessage(data);
        });

        this.refresh();
    }

    private async handleMessage(data: any) {
        switch (data.type) {
            case 'commit':
                await this.doCommit(data.message, data.amend, false);
                break;
            case 'commitAndPush':
                await this.doCommit(data.message, data.amend, true);
                break;
            case 'refresh':
                await this.refresh();
                break;
            case 'rollback':
                await this.rollbackFile(data.path);
                break;
            case 'showDiff':
                await this.showDiff(data.path);
                break;
            case 'toggleFile':
                this.toggleFileSelection(data.path, data.checked);
                break;
            case 'toggleAll':
                this.toggleAllFiles(data.checked);
                break;
            case 'addToVcs':
                await this.addToVcs(data.path);
                break;
            case 'moveToChangelist':
                await this.moveToChangelist(data.path);
                break;
            case 'newChangelist':
                await this.createNewChangelist();
                break;
            case 'openFile':
                await this.openFile(data.path);
                break;
        }
    }

    public async refresh(): Promise<void> {
        this.fileChanges = await this.gitService.getFileChanges(this.repoPath);
        this.currentBranch = await this.gitService.getCurrentBranch(this.repoPath);
        this.commitStatus = await this.gitService.getCommitStatus(this.repoPath);

        // Select all files by default
        this.selectedFiles = new Set(this.fileChanges.map(f => f.path));

        this.updateWebview();
    }

    private updateWebview() {
        if (!this._view) return;

        const trackedChanges = this.fileChanges.filter(f => f.status !== FileStatus.Untracked);
        const untrackedFiles = this.fileChanges.filter(f => f.status === FileStatus.Untracked);

        // Build trees for each changelist
        const changelistsData = this.changelists.map(cl => {
            const clFiles = trackedChanges.filter(f => {
                const assignedCl = this.fileToChangelist.get(f.path);
                // If file has no assigned changelist, it belongs to default
                return assignedCl === cl.id || (!assignedCl && cl.id === 'default');
            });
            return {
                id: cl.id,
                name: cl.name,
                tree: this.buildTree(clFiles),
                fileCount: clFiles.length
            };
        });

        const untrackedTree = this.buildTree(untrackedFiles);

        this._view.webview.postMessage({
            type: 'update',
            changelists: changelistsData,
            untracked: untrackedTree,
            branch: this.currentBranch,
            ahead: this.commitStatus.ahead,
            behind: this.commitStatus.behind,
            selectedFiles: Array.from(this.selectedFiles),
            totalFiles: this.fileChanges.length
        });
    }

    private buildTree(files: FileChange[]): FileNode[] {
        const root: Map<string, FileNode> = new Map();

        for (const file of files) {
            const parts = file.path.split('/');
            let currentPath = '';

            for (let i = 0; i < parts.length; i++) {
                const part = parts[i];
                const isFile = i === parts.length - 1;
                const parentPath = currentPath;
                currentPath = currentPath ? `${currentPath}/${part}` : part;

                if (isFile) {
                    const fileNode: FileNode = {
                        type: 'file',
                        name: part,
                        path: file.path,
                        status: file.status,
                        checked: this.selectedFiles.has(file.path)
                    };

                    if (parts.length === 1) {
                        root.set(currentPath, fileNode);
                    } else {
                        const parent = this.findNode(root, parentPath);
                        if (parent?.children) {
                            if (!parent.children.find(c => c.path === file.path)) {
                                parent.children.push(fileNode);
                            }
                        }
                    }
                } else {
                    let dirNode = this.findNode(root, currentPath);
                    if (!dirNode) {
                        dirNode = {
                            type: 'directory',
                            name: part,
                            path: currentPath,
                            children: []
                        };
                        if (i === 0) {
                            root.set(currentPath, dirNode);
                        } else {
                            const parent = this.findNode(root, parentPath);
                            if (parent?.children) {
                                parent.children.push(dirNode);
                            }
                        }
                    }
                }
            }
        }

        return this.sortTree(Array.from(root.values()));
    }

    private findNode(root: Map<string, FileNode>, targetPath: string): FileNode | undefined {
        for (const [_, node] of root) {
            if (node.path === targetPath) return node;
            if (node.children) {
                const found = this.findInChildren(node.children, targetPath);
                if (found) return found;
            }
        }
        return undefined;
    }

    private findInChildren(children: FileNode[], targetPath: string): FileNode | undefined {
        for (const child of children) {
            if (child.path === targetPath) return child;
            if (child.children) {
                const found = this.findInChildren(child.children, targetPath);
                if (found) return found;
            }
        }
        return undefined;
    }

    private sortTree(nodes: FileNode[]): FileNode[] {
        return nodes.sort((a, b) => {
            if (a.type === 'directory' && b.type === 'file') return -1;
            if (a.type === 'file' && b.type === 'directory') return 1;
            return a.name.localeCompare(b.name);
        }).map(node => {
            if (node.children) {
                node.children = this.sortTree(node.children);
            }
            return node;
        });
    }

    private async doCommit(message: string, amend: boolean, andPush: boolean) {
        if (!message.trim() && !amend) {
            vscode.window.showWarningMessage('Please enter a commit message');
            return;
        }

        try {
            // Stage selected files
            for (const filePath of this.selectedFiles) {
                await this.gitService.stage(this.repoPath, path.join(this.repoPath, filePath));
            }

            if (amend) {
                await this.gitService.commitAmend(this.repoPath, message || undefined);
            } else {
                await this.gitService.commitStaged(this.repoPath, message);
            }

            if (andPush) {
                await this.gitService.push(this.repoPath);
                vscode.window.showInformationMessage('Committed and pushed successfully');
            } else {
                vscode.window.showInformationMessage('Committed successfully');
            }

            await this.refresh();
            this._view?.webview.postMessage({ type: 'commitComplete' });
        } catch (error: any) {
            vscode.window.showErrorMessage(`Commit failed: ${error.message}`);
        }
    }

    private async rollbackFile(filePath: string) {
        const confirm = await vscode.window.showWarningMessage(
            `Rollback changes to ${filePath}?`,
            { modal: true },
            'Rollback'
        );
        if (confirm === 'Rollback') {
            await this.gitService.discardChanges(this.repoPath, path.join(this.repoPath, filePath));
            await this.refresh();
        }
    }

    private async showDiff(filePath: string) {
        const uri = vscode.Uri.file(path.join(this.repoPath, filePath));
        try {
            await vscode.commands.executeCommand('git.openChange', uri);
        } catch (error) {
            console.error('Failed to open git change:', error);
            await vscode.commands.executeCommand('vscode.open', uri);
        }
    }

    private async openFile(filePath: string) {
        const uri = vscode.Uri.file(path.join(this.repoPath, filePath));
        await vscode.commands.executeCommand('vscode.open', uri);
    }

    private toggleFileSelection(filePath: string, checked: boolean) {
        if (checked) {
            this.selectedFiles.add(filePath);
        } else {
            this.selectedFiles.delete(filePath);
        }
    }

    private toggleAllFiles(checked: boolean) {
        if (checked) {
            this.selectedFiles = new Set(this.fileChanges.map(f => f.path));
        } else {
            this.selectedFiles.clear();
        }
        this.updateWebview();
    }

    private async addToVcs(filePath: string) {
        await this.gitService.stage(this.repoPath, path.join(this.repoPath, filePath));
        await this.refresh();
    }

    private async moveToChangelist(filePath: string) {
        // Show quick pick with all changelists
        const items = this.changelists.map(cl => ({
            label: cl.name,
            description: cl.id === 'default' ? '(default)' : '',
            id: cl.id
        }));

        items.push({
            label: '+ New Changelist...',
            description: '',
            id: '__new__'
        });

        const selected = await vscode.window.showQuickPick(items, {
            placeHolder: 'Select changelist to move file to'
        });

        if (selected) {
            if (selected.id === '__new__') {
                const newName = await vscode.window.showInputBox({
                    placeHolder: 'Changelist name',
                    prompt: 'Enter new changelist name'
                });
                if (newName) {
                    const newId = `cl_${Date.now()}`;
                    this.changelists.push({
                        id: newId,
                        name: newName,
                        files: new Set([filePath])
                    });
                    this.fileToChangelist.set(filePath, newId);
                    this.updateWebview();
                    vscode.window.showInformationMessage(`Moved to new changelist: ${newName}`);
                }
            } else {
                this.fileToChangelist.set(filePath, selected.id);
                this.updateWebview();
                vscode.window.showInformationMessage(`Moved to: ${selected.label}`);
            }
        }
    }

    private async createNewChangelist() {
        const name = await vscode.window.showInputBox({
            placeHolder: 'Changelist name',
            prompt: 'Enter new changelist name'
        });
        if (name) {
            const newId = `cl_${Date.now()}`;
            this.changelists.push({
                id: newId,
                name: name,
                files: new Set()
            });
            this.updateWebview();
            vscode.window.showInformationMessage(`Created changelist: ${name}`);
        }
    }

    private getHtmlContent(): string {
        return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
            font-size: 13px;
            color: var(--vscode-foreground);
            background: var(--vscode-sideBar-background);
            height: 100vh;
            display: flex;
            flex-direction: column;
            line-height: 1.4;
        }
        
        /* Tabs */
        .tabs {
            display: flex;
            border-bottom: 1px solid var(--vscode-panel-border);
            background: var(--vscode-sideBar-background);
            flex-shrink: 0;
        }
        .tab {
            padding: 6px 12px;
            cursor: pointer;
            color: var(--vscode-foreground);
            font-size: 12px;
            opacity: 0.7;
            border-bottom: 2px solid transparent;
            margin-bottom: -1px;
        }
        .tab:hover { opacity: 1; }
        .tab.active {
            opacity: 1;
            border-bottom-color: var(--vscode-focusBorder);
        }
        
        /* Toolbar */
        .toolbar {
            display: flex;
            align-items: center;
            gap: 2px;
            padding: 4px 6px;
            border-bottom: 1px solid var(--vscode-panel-border);
            flex-shrink: 0;
        }
        .toolbar-btn {
            background: transparent;
            border: none;
            color: var(--vscode-foreground);
            width: 22px;
            height: 22px;
            cursor: pointer;
            border-radius: 3px;
            opacity: 0.6;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 0;
        }
        .toolbar-btn:hover {
            background: var(--vscode-toolbar-hoverBackground);
            opacity: 1;
        }
        .toolbar-btn svg { width: 14px; height: 14px; fill: currentColor; }
        .toolbar-sep {
            width: 1px;
            height: 14px;
            background: var(--vscode-panel-border);
            margin: 0 3px;
        }
        
        /* Content */
        .content {
            flex: 1;
            overflow-y: auto;
            overflow-x: hidden;
        }
        
        /* Section */
        .section-header {
            display: flex;
            align-items: center;
            padding: 4px 8px;
            cursor: pointer;
            user-select: none;
        }
        .section-header:hover {
            background: var(--vscode-list-hoverBackground);
        }
        .section-arrow {
            font-size: 10px;
            width: 14px;
            opacity: 0.7;
            flex-shrink: 0;
        }
        .section-checkbox {
            width: 13px;
            height: 13px;
            margin: 0 4px;
            cursor: pointer;
            flex-shrink: 0;
        }
        .section-icon {
            width: 16px;
            height: 16px;
            margin-right: 4px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            flex-shrink: 0;
        }
        .section-icon svg {
            width: 14px;
            height: 14px;
        }
        .section-title {
            font-weight: 600;
            font-size: 12px;
        }
        .section-count {
            margin-left: 6px;
            opacity: 0.5;
            font-weight: normal;
            font-size: 12px;
        }
        
        /* Tree row */
        .tree-row {
            display: flex;
            align-items: center;
            min-height: 22px;
            padding-right: 8px;
            cursor: pointer;
        }
        .tree-row:hover {
            background: var(--vscode-list-hoverBackground);
        }
        .tree-row.selected {
            background: var(--vscode-list-activeSelectionBackground);
        }
        
        /* Indent */
        .tree-indent {
            flex-shrink: 0;
        }
        .indent-guide {
            display: inline-block;
            width: 16px;
            height: 22px;
            position: relative;
        }
        .indent-guide.line::before {
            content: '';
            position: absolute;
            left: 11px;
            top: 0;
            bottom: 0;
            width: 1px;
            background: var(--vscode-tree-indentGuidesStroke, rgba(128,128,128,0.2));
        }
        
        /* Arrow */
        .tree-arrow {
            width: 16px;
            height: 22px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            font-size: 10px;
            opacity: 0.6;
            flex-shrink: 0;
        }
        .tree-arrow.hidden { visibility: hidden; }
        
        /* Checkbox */
        .tree-checkbox {
            width: 13px;
            height: 13px;
            margin: 0 4px 0 0;
            cursor: pointer;
            flex-shrink: 0;
        }
        
        /* Icon */
        .tree-icon {
            width: 16px;
            height: 16px;
            margin-right: 4px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            flex-shrink: 0;
        }
        .tree-icon svg {
            width: 14px;
            height: 14px;
        }
        
        /* Name */
        .tree-name {
            flex: 1;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            font-size: 12px;
        }
        .tree-count {
            opacity: 0.5;
            margin-left: 6px;
            font-size: 11px;
        }
        
        /* Status */
        .tree-status {
            flex-shrink: 0;
            font-size: 11px;
            width: 20px;
            text-align: right;
        }
        .tree-status.M { color: #6cb5ff; }
        .tree-status.A { color: #73c991; }
        .tree-status.D { color: #f87571; }
        .tree-status.U, .tree-status.\\? { color: #cfa6ff; }
        
        /* New changelist */
        .new-changelist {
            padding: 4px 8px 4px 46px;
            font-size: 12px;
            color: var(--vscode-textLink-foreground);
            cursor: pointer;
            opacity: 0.8;
        }
        .new-changelist:hover {
            opacity: 1;
            text-decoration: underline;
        }
        
        /* Context menu */
        .context-menu {
            position: fixed;
            background: var(--vscode-menu-background);
            border: 1px solid var(--vscode-menu-border);
            border-radius: 4px;
            padding: 4px 0;
            min-width: 180px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.3);
            z-index: 10000;
            display: none;
            font-size: 12px;
        }
        .context-menu.show { display: block; }
        .context-menu-item {
            padding: 4px 12px;
            cursor: pointer;
        }
        .context-menu-item:hover {
            background: var(--vscode-menu-selectionBackground);
        }
        .context-menu-sep {
            height: 1px;
            background: var(--vscode-menu-separatorBackground);
            margin: 4px 0;
        }
    </style>
</head>
<body>
    <!-- Tabs -->
    <div class="tabs">
        <div class="tab active">Commit</div>
        <div class="tab">Shelf</div>
    </div>
    
    <!-- Toolbar -->
    <div class="toolbar">
        <button class="toolbar-btn" onclick="refresh()" title="Refresh">
            <svg viewBox="0 0 16 16"><path d="M2.5 8a5.5 5.5 0 1 1 .67 2.63.5.5 0 0 0-.87.5A6.5 6.5 0 1 0 1.5 8H.25l1.5 2 1.5-2H2.5z"/></svg>
        </button>
        <button class="toolbar-btn" onclick="rollbackSelected()" title="Rollback">
            <svg viewBox="0 0 16 16"><path d="M2 2.5A.5.5 0 0 1 2.5 2h11a.5.5 0 0 1 .5.5v11a.5.5 0 0 1-.5.5h-11a.5.5 0 0 1-.5-.5v-11zM3 3v10h10V3H3z"/><path d="M5.5 7.5a.5.5 0 0 1 .5-.5h4a.5.5 0 0 1 0 1H6a.5.5 0 0 1-.5-.5z"/></svg>
        </button>
        <button class="toolbar-btn" onclick="shelve()" title="Shelve">
            <svg viewBox="0 0 16 16"><path d="M2 2h12v3H2V2zm0 4h12v3H2V6zm0 4h12v4H2v-4z"/></svg>
        </button>
        <button class="toolbar-btn" title="Unshelve">
            <svg viewBox="0 0 16 16"><path d="M8 1l3 4H9v4H7V5H5l3-4zM2 10h12v4H2v-4z"/></svg>
        </button>
        <div class="toolbar-sep"></div>
        <button class="toolbar-btn" onclick="showDiffSelected()" title="Show Diff">
            <svg viewBox="0 0 16 16"><path d="M8 3a.5.5 0 0 1 .5.5v4h4a.5.5 0 0 1 0 1h-4v4a.5.5 0 0 1-1 0v-4h-4a.5.5 0 0 1 0-1h4v-4A.5.5 0 0 1 8 3z"/></svg>
        </button>
        <button class="toolbar-btn" title="Group by Directory">
            <svg viewBox="0 0 16 16"><path d="M1 3.5A1.5 1.5 0 0 1 2.5 2h3.879a1.5 1.5 0 0 1 1.06.44l1.122 1.12A1.5 1.5 0 0 0 9.62 4H13.5A1.5 1.5 0 0 1 15 5.5v8a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 1 13.5v-10zM2.5 3a.5.5 0 0 0-.5.5v10a.5.5 0 0 0 .5.5h11a.5.5 0 0 0 .5-.5v-8a.5.5 0 0 0-.5-.5H9.62a2.5 2.5 0 0 1-1.768-.732L6.732 3.146A.5.5 0 0 0 6.379 3H2.5z"/></svg>
        </button>
        <div class="toolbar-sep"></div>
        <button class="toolbar-btn" onclick="newChangelist()" title="New Changelist">
            <svg viewBox="0 0 16 16"><path d="M8 3a.5.5 0 0 1 .5.5v4h4a.5.5 0 0 1 0 1h-4v4a.5.5 0 0 1-1 0v-4h-4a.5.5 0 0 1 0-1h4v-4A.5.5 0 0 1 8 3z"/></svg>
        </button>
    </div>
    
    <!-- Content -->
    <div class="content">
        <!-- Changelists (dynamically rendered) -->
        <div id="changelistsContainer"></div>
        
        <!-- Unversioned -->
        <div id="untrackedSection" style="display:none;">
            <div class="section-header" onclick="toggleSection('untracked')">
                <span class="section-arrow" id="untrackedArrow">▼</span>
                <input type="checkbox" class="section-checkbox" id="untrackedCheckbox" onclick="event.stopPropagation(); toggleAllUntracked(this.checked)">
                <span class="section-icon"><svg viewBox="0 0 16 16" fill="#b8a67a"><path d="M1 3.5A1.5 1.5 0 0 1 2.5 2h3.879a1.5 1.5 0 0 1 1.06.44l.94.94H13.5A1.5 1.5 0 0 1 15 4.88V5H1V3.5zM1 6h14v7.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 1 13.5V6z"/></svg></span>
                <span class="section-title">Unversioned Files</span>
                <span class="section-count" id="untrackedCount">0 files</span>
            </div>
            <div id="untrackedTree"></div>
        </div>
    </div>
    
    <!-- Context Menu -->
    <div class="context-menu" id="contextMenu">
        <div class="context-menu-item" onclick="showDiff()">Show Diff</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" onclick="rollbackFile()">Rollback...</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" onclick="moveToChangelist()">Move to Another Changelist...</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" onclick="addToVcs()">Add to VCS</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" onclick="openFile()">Jump to Source</div>
    </div>
    
    <script>
        const vscode = acquireVsCodeApi();
        let currentData = { changes: [], untracked: [], selectedFiles: [] };
        let contextFilePath = null;
        let expandedSections = { changes: true, untracked: true };
        let expandedDirs = new Set();
        
        // SVG Icons
        const icons = {
            folder: '<svg viewBox="0 0 16 16" fill="#b8a67a"><path d="M1 3.5A1.5 1.5 0 0 1 2.5 2h3.879a1.5 1.5 0 0 1 1.06.44l.94.94H13.5A1.5 1.5 0 0 1 15 4.88V5H1V3.5zM1 6h14v7.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 1 13.5V6z"/></svg>',
            folderOpen: '<svg viewBox="0 0 16 16" fill="#b8a67a"><path d="M1 3.5A1.5 1.5 0 0 1 2.5 2h3.879a1.5 1.5 0 0 1 1.06.44l.94.94H13.5A1.5 1.5 0 0 1 15 4.88V5H1V3.5zM.5 7h13l-1.5 7H2L.5 7z"/></svg>',
            file: '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M4 1h5.586a1 1 0 0 1 .707.293l3.414 3.414a1 1 0 0 1 .293.707V14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1z"/></svg>',
            php: '<svg viewBox="0 0 16 16"><ellipse fill="#777bb4" cx="8" cy="8" rx="7" ry="4"/><text x="8" y="9.5" text-anchor="middle" fill="white" font-size="5" font-weight="bold">php</text></svg>',
            js: '<svg viewBox="0 0 16 16"><rect fill="#f7df1e" width="16" height="16" rx="2"/><text x="8" y="12" text-anchor="middle" fill="#333" font-size="8" font-weight="bold">JS</text></svg>',
            ts: '<svg viewBox="0 0 16 16"><rect fill="#3178c6" width="16" height="16" rx="2"/><text x="8" y="12" text-anchor="middle" fill="white" font-size="8" font-weight="bold">TS</text></svg>',
            json: '<svg viewBox="0 0 16 16"><path fill="#cbcb41" d="M2 2h12v12H2z"/><text x="8" y="11" text-anchor="middle" fill="#333" font-size="6" font-weight="bold">{}</text></svg>',
            html: '<svg viewBox="0 0 16 16"><path fill="#e34c26" d="M1 1l1.3 13L8 15l5.7-1L15 1H1zm10.5 4H5.8l.2 1.8h5.3l-.5 5.2-2.8.8-2.8-.8-.2-2h1.8l.1 1 1.1.3 1.1-.3.1-1.5H4.8L4.3 4h7.4l-.2 1z"/></svg>',
            css: '<svg viewBox="0 0 16 16"><path fill="#264de4" d="M1 1l1.3 13L8 15l5.7-1L15 1H1zm10.4 4H5l.2 2h6l-.4 4.5L8 12.3l-2.8-.8L5 9h2l.1 1.4L8 10.8l.9-.4.1-1.4H5.3L4.8 4h6.4l-.2 1z"/></svg>',
            vue: '<svg viewBox="0 0 16 16"><path fill="#41b883" d="M8 12L2 2h3l3 5 3-5h3L8 12z"/><path fill="#35495e" d="M8 9L5 4h1.5l1.5 2.5L9.5 4H11L8 9z"/></svg>',
            md: '<svg viewBox="0 0 16 16"><rect fill="none" stroke="#519aba" stroke-width="1" x="1" y="3" width="14" height="10" rx="1"/><text x="8" y="10" text-anchor="middle" fill="#519aba" font-size="5" font-weight="bold">MD</text></svg>',
            xml: '<svg viewBox="0 0 16 16"><path fill="#e37933" d="M4 1h5.586a1 1 0 0 1 .707.293l3.414 3.414a1 1 0 0 1 .293.707V14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1z"/><text x="8" y="11" text-anchor="middle" fill="white" font-size="5">&lt;/&gt;</text></svg>',
            blade: '<svg viewBox="0 0 16 16"><path fill="#f05340" d="M4 1h5.586a1 1 0 0 1 .707.293l3.414 3.414a1 1 0 0 1 .293.707V14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1z"/><path fill="white" d="M5 7h6v1H5zM5 9h4v1H5z"/></svg>',
        };
        
        function getFileIcon(name) {
            const ext = name.split('.').pop().toLowerCase();
            if (name.endsWith('.blade.php')) return icons.blade;
            switch(ext) {
                case 'php': return icons.php;
                case 'js': case 'mjs': case 'cjs': return icons.js;
                case 'ts': case 'tsx': return icons.ts;
                case 'json': return icons.json;
                case 'html': case 'htm': return icons.html;
                case 'css': case 'scss': case 'sass': return icons.css;
                case 'vue': return icons.vue;
                case 'md': return icons.md;
                case 'xml': return icons.xml;
                default: return icons.file;
            }
        }
        
        function refresh() { vscode.postMessage({ type: 'refresh' }); }
        
        function toggleSection(sectionId) {
            expandedSections[sectionId] = expandedSections[sectionId] === false ? true : !expandedSections[sectionId];
            const isExpanded = expandedSections[sectionId] !== false;
            
            // Try both naming conventions (for static untracked and dynamic changelists)
            const arrow = document.getElementById('arrow_' + sectionId) || document.getElementById(sectionId + 'Arrow');
            const tree = document.getElementById('tree_' + sectionId) || document.getElementById(sectionId + 'Tree');
            
            if (arrow) arrow.textContent = isExpanded ? '▼' : '▶';
            if (tree) tree.style.display = isExpanded ? 'block' : 'none';
        }
        
        function toggleAllChanges(checked) {
            vscode.postMessage({ type: 'toggleAll', checked });
        }
        
        function toggleAllUntracked(checked) {
            // Toggle untracked files
        }
        
        function renderTree(nodes, container, depth = 0, parentHasMore = []) {
            nodes.forEach((node, idx) => {
                const isLast = idx === nodes.length - 1;
                const div = document.createElement('div');
                div.className = 'tree-row';
                div.setAttribute('data-path', node.path);
                
                // Build indent guides
                let indent = '';
                for (let i = 0; i < depth; i++) {
                    indent += '<span class="indent-guide' + (parentHasMore[i] ? ' line' : '') + '"></span>';
                }
                
                if (node.type === 'directory') {
                    const isExpanded = expandedDirs.has(node.path);
                    const fileCount = countFiles(node);
                    const allChecked = areAllChecked(node);
                    const someChecked = areSomeChecked(node);
                    
                    div.innerHTML = \`
                        <span class="tree-indent">\${indent}</span>
                        <span class="tree-arrow" onclick="event.stopPropagation(); toggleDir('\${node.path}')">\${isExpanded ? '▼' : '▶'}</span>
                        <input type="checkbox" class="tree-checkbox" \${allChecked ? 'checked' : ''} \${someChecked && !allChecked ? 'indeterminate' : ''} onclick="event.stopPropagation(); toggleDirCheck('\${node.path}', this.checked)">
                        <span class="tree-icon">\${isExpanded ? icons.folderOpen : icons.folder}</span>
                        <span class="tree-name">\${node.name}</span>
                        <span class="tree-count">\${fileCount} file\${fileCount !== 1 ? 's' : ''}</span>
                    \`;
                    div.ondblclick = () => toggleDir(node.path);
                    container.appendChild(div);
                    
                    if (isExpanded && node.children) {
                        const newParentHasMore = [...parentHasMore, !isLast];
                        renderTree(node.children, container, depth + 1, newParentHasMore);
                    }
                } else {
                    div.innerHTML = \`
                        <span class="tree-indent">\${indent}</span>
                        <span class="tree-arrow hidden">▶</span>
                        <input type="checkbox" class="tree-checkbox" \${node.checked ? 'checked' : ''} onclick="event.stopPropagation(); toggleFile('\${node.path}', this.checked)">
                        <span class="tree-icon">\${getFileIcon(node.name)}</span>
                        <span class="tree-name">\${node.name}</span>
                        <span class="tree-status \${node.status}">\${node.status}</span>
                    \`;
                    div.ondblclick = () => showDiffFor(node.path);
                    div.oncontextmenu = (e) => showContextMenu(e, node.path);
                    container.appendChild(div);
                }
            });
        }
        
        function countFiles(node) {
            if (!node.children) return 0;
            let count = 0;
            node.children.forEach(c => {
                if (c.type === 'file') count++;
                else count += countFiles(c);
            });
            return count;
        }
        
        function areAllChecked(node) {
            if (!node.children) return false;
            return node.children.every(c => c.type === 'file' ? c.checked : areAllChecked(c));
        }
        
        function areSomeChecked(node) {
            if (!node.children) return false;
            return node.children.some(c => c.type === 'file' ? c.checked : areSomeChecked(c));
        }
        
        function toggleDir(path) {
            if (expandedDirs.has(path)) expandedDirs.delete(path);
            else expandedDirs.add(path);
            update(currentData);
        }
        
        function toggleDirCheck(path, checked) {
            // Find all files under this directory and toggle them
            function getFilePaths(nodes, targetPath) {
                let paths = [];
                for (const n of nodes) {
                    if (n.path === targetPath || n.path.startsWith(targetPath + '/')) {
                        if (n.type === 'file') paths.push(n.path);
                        if (n.children) paths = paths.concat(getFilePaths(n.children, targetPath));
                    }
                    if (n.children) paths = paths.concat(getFilePaths(n.children, targetPath));
                }
                return paths;
            }
            const allNodes = [...currentData.changes, ...currentData.untracked];
            const filePaths = getFilePaths(allNodes, path);
            filePaths.forEach(fp => vscode.postMessage({ type: 'toggleFile', path: fp, checked }));
        }
        
        function toggleFile(path, checked) {
            vscode.postMessage({ type: 'toggleFile', path, checked });
        }
        
        function showDiffFor(path) {
            vscode.postMessage({ type: 'showDiff', path });
        }
        
        function showContextMenu(e, path) {
            e.preventDefault();
            e.stopPropagation();
            contextFilePath = path;
            const menu = document.getElementById('contextMenu');
            const x = Math.min(e.clientX, window.innerWidth - 200);
            const y = Math.min(e.clientY, window.innerHeight - 200);
            menu.style.left = x + 'px';
            menu.style.top = y + 'px';
            menu.classList.add('show');
        }
        
        document.addEventListener('click', () => {
            document.getElementById('contextMenu').classList.remove('show');
        });
        
        function showDiff() {
            if (contextFilePath) vscode.postMessage({ type: 'showDiff', path: contextFilePath });
        }
        
        function rollbackFile() {
            if (contextFilePath) vscode.postMessage({ type: 'rollback', path: contextFilePath });
        }
        
        function addToVcs() {
            if (contextFilePath) vscode.postMessage({ type: 'addToVcs', path: contextFilePath });
        }
        
        function moveToChangelist() {
            if (contextFilePath) vscode.postMessage({ type: 'moveToChangelist', path: contextFilePath });
        }
        
        function openFile() {
            if (contextFilePath) vscode.postMessage({ type: 'openFile', path: contextFilePath });
        }
        
        function newChangelist() {
            vscode.postMessage({ type: 'newChangelist' });
        }
        
        function shelve() { alert('Shelve - Coming soon'); }
        function rollbackSelected() { alert('Rollback selected - Coming soon'); }
        function showDiffSelected() { alert('Show diff selected - Coming soon'); }
        
        function update(data) {
            currentData = data;
            
            // Collect dirs from all changelists
            if (expandedDirs.size === 0 && data.changelists) {
                data.changelists.forEach(cl => collectDirs(cl.tree));
                collectDirs(data.untracked);
            }
            
            // Render changelists
            const container = document.getElementById('changelistsContainer');
            container.innerHTML = '';
            
            if (data.changelists) {
                data.changelists.forEach((cl, idx) => {
                    const isExpanded = expandedSections[cl.id] !== false; // default expanded
                    
                    const section = document.createElement('div');
                    section.id = 'section_' + cl.id;
                    
                    section.innerHTML = \`
                        <div class="section-header" onclick="toggleSection('\${cl.id}')">
                            <span class="section-arrow" id="arrow_\${cl.id}">\${isExpanded ? '▼' : '▶'}</span>
                            <input type="checkbox" class="section-checkbox" checked onclick="event.stopPropagation();">
                            <span class="section-icon">\${icons.folder}</span>
                            <span class="section-title">\${cl.name}</span>
                            <span class="section-count">\${cl.fileCount} files</span>
                        </div>
                        <div id="tree_\${cl.id}" style="display: \${isExpanded ? 'block' : 'none'}"></div>
                    \`;
                    container.appendChild(section);
                    
                    // Render tree
                    const treeContainer = section.querySelector('#tree_' + cl.id);
                    renderTree(cl.tree, treeContainer);
                });
                
                // Add "New changelist" link
                const newClLink = document.createElement('div');
                newClLink.className = 'new-changelist';
                newClLink.textContent = 'New changelist';
                newClLink.onclick = () => newChangelist();
                container.appendChild(newClLink);
            }
            
            // Render untracked
            const untrackedTree = document.getElementById('untrackedTree');
            untrackedTree.innerHTML = '';
            const untrackedCount = countAllFiles(data.untracked);
            document.getElementById('untrackedCount').textContent = untrackedCount + ' files';
            renderTree(data.untracked, untrackedTree);
            document.getElementById('untrackedSection').style.display = untrackedCount > 0 ? 'block' : 'none';
        }
        
        function countAllFiles(nodes) {
            if (!nodes) return 0;
            let count = 0;
            nodes.forEach(n => {
                if (n.type === 'file') count++;
                if (n.children) count += countAllFiles(n.children);
            });
            return count;
        }
        
        function collectDirs(nodes) {
            if (!nodes) return;
            nodes.forEach(n => {
                if (n.type === 'directory') {
                    expandedDirs.add(n.path);
                    if (n.children) collectDirs(n.children);
                }
            });
        }
        
        window.addEventListener('message', event => {
            const msg = event.data;
            if (msg.type === 'update') update(msg);
        });
    </script>
</body>
</html>`;
    }
}
