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

export class GitAbcPanelProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'gitabcPanel';
    private _view?: vscode.WebviewView;
    private fileChanges: FileChange[] = [];
    private selectedFiles: Set<string> = new Set();
    private currentBranch: string = 'main';
    private commitStatus = { ahead: 0, behind: 0 };

    constructor(
        private readonly extensionUri: vscode.Uri,
        private gitService: GitService,
        private repoPath: string
    ) { }

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

        const changesTree = this.buildTree(trackedChanges);
        const untrackedTree = this.buildTree(untrackedFiles);

        this._view.webview.postMessage({
            type: 'update',
            changes: changesTree,
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

    private countFiles(nodes: FileNode[]): number {
        let count = 0;
        for (const node of nodes) {
            if (node.type === 'file') count++;
            if (node.children) count += this.countFiles(node.children);
        }
        return count;
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
        const originalUri = this.gitService.getOriginalUri(uri);
        const title = `${filePath} (Working Tree)`;
        await vscode.commands.executeCommand('vscode.diff', originalUri, uri, title);
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
        vscode.window.showInformationMessage(`Move ${filePath} to changelist - Coming soon`);
    }

    private async createNewChangelist() {
        const name = await vscode.window.showInputBox({
            placeHolder: 'Changelist name',
            prompt: 'Enter new changelist name'
        });
        if (name) {
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
            font-family: var(--vscode-font-family);
            font-size: 13px;
            color: var(--vscode-foreground);
            background: var(--vscode-sideBar-background);
            height: 100vh;
            display: flex;
            flex-direction: column;
        }
        
        /* Toolbar */
        .toolbar {
            display: flex;
            gap: 2px;
            padding: 6px 8px;
            border-bottom: 1px solid var(--vscode-panel-border);
            flex-shrink: 0;
        }
        .toolbar button {
            background: transparent;
            border: none;
            color: var(--vscode-foreground);
            padding: 4px 6px;
            cursor: pointer;
            border-radius: 3px;
            font-size: 14px;
            opacity: 0.8;
        }
        .toolbar button:hover {
            background: var(--vscode-toolbar-hoverBackground);
            opacity: 1;
        }
        .toolbar .separator {
            width: 1px;
            background: var(--vscode-panel-border);
            margin: 0 4px;
        }
        
        /* Changes area */
        .changes-area {
            flex: 1;
            overflow-y: auto;
            padding: 4px 0;
        }
        
        /* Section */
        .section {
            margin-bottom: 4px;
        }
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
        .section-header .arrow {
            width: 16px;
            font-size: 10px;
        }
        .section-header .icon {
            margin-right: 6px;
        }
        .section-header .title {
            font-weight: 500;
        }
        .section-header .count {
            margin-left: 6px;
            opacity: 0.7;
        }
        
        /* Tree */
        .tree { padding-left: 0; }
        .tree-item {
            display: flex;
            align-items: center;
            padding: 2px 8px 2px 24px;
            cursor: pointer;
        }
        .tree-item:hover {
            background: var(--vscode-list-hoverBackground);
        }
        .tree-item.selected {
            background: var(--vscode-list-activeSelectionBackground);
            color: var(--vscode-list-activeSelectionForeground);
        }
        .tree-item input[type="checkbox"] {
            margin-right: 6px;
            cursor: pointer;
        }
        .tree-item .arrow {
            width: 16px;
            font-size: 10px;
            flex-shrink: 0;
        }
        .tree-item .icon {
            margin-right: 6px;
            font-size: 14px;
        }
        .tree-item .name {
            flex: 1;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .tree-item .status {
            margin-left: 8px;
            font-weight: 500;
            width: 14px;
            text-align: center;
        }
        .tree-item .status.M { color: #4FC3F7; }
        .tree-item .status.A { color: #81C784; }
        .tree-item .status.D { color: #E57373; }
        .tree-item .status.U { color: #BA68C8; }
        
        /* Indent levels */
        .indent-1 { padding-left: 40px; }
        .indent-2 { padding-left: 56px; }
        .indent-3 { padding-left: 72px; }
        .indent-4 { padding-left: 88px; }
        .indent-5 { padding-left: 104px; }
        
        /* Context menu */
        .context-menu {
            position: fixed;
            background: var(--vscode-menu-background);
            border: 1px solid var(--vscode-menu-border);
            border-radius: 4px;
            padding: 4px 0;
            min-width: 180px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.3);
            z-index: 1000;
            display: none;
        }
        .context-menu.show { display: block; }
        .context-menu-item {
            padding: 6px 16px;
            cursor: pointer;
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .context-menu-item:hover {
            background: var(--vscode-menu-selectionBackground);
        }
        .context-menu-separator {
            height: 1px;
            background: var(--vscode-menu-separatorBackground);
            margin: 4px 0;
        }
        
        /* Amend & Commit area */
        .commit-area {
            border-top: 1px solid var(--vscode-panel-border);
            padding: 8px;
            flex-shrink: 0;
        }
        .amend-row {
            display: flex;
            align-items: center;
            margin-bottom: 8px;
            gap: 8px;
        }
        .amend-row label {
            display: flex;
            align-items: center;
            gap: 4px;
            cursor: pointer;
        }
        .commit-message {
            width: 100%;
            min-height: 60px;
            max-height: 120px;
            resize: vertical;
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            border: 1px solid var(--vscode-input-border);
            border-radius: 2px;
            padding: 8px;
            font-family: var(--vscode-font-family);
            font-size: 13px;
            margin-bottom: 8px;
        }
        .commit-message:focus {
            outline: 1px solid var(--vscode-focusBorder);
        }
        .commit-message::placeholder {
            color: var(--vscode-input-placeholderForeground);
        }
        .commit-buttons {
            display: flex;
            gap: 8px;
        }
        .commit-buttons button {
            padding: 6px 14px;
            border: none;
            border-radius: 2px;
            cursor: pointer;
            font-size: 13px;
        }
        .commit-buttons .primary {
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
        }
        .commit-buttons .primary:hover {
            background: var(--vscode-button-hoverBackground);
        }
        .commit-buttons .secondary {
            background: var(--vscode-button-secondaryBackground);
            color: var(--vscode-button-secondaryForeground);
        }
        .commit-buttons .secondary:hover {
            background: var(--vscode-button-secondaryHoverBackground);
        }
        .commit-buttons button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        /* Status bar */
        .status-bar {
            padding: 4px 8px;
            font-size: 11px;
            color: var(--vscode-descriptionForeground);
            border-top: 1px solid var(--vscode-panel-border);
        }
        .status-bar .branch {
            color: var(--vscode-textLink-foreground);
        }
    </style>
</head>
<body>
    <!-- Toolbar -->
    <div class="toolbar">
        <button onclick="refresh()" title="Refresh">⟳</button>
        <button onclick="rollbackSelected()" title="Rollback">↩</button>
        <button onclick="shelve()" title="Shelve Changes">📦</button>
        <div class="separator"></div>
        <button onclick="showDiffSelected()" title="Show Diff">⇋</button>
        <div class="separator"></div>
        <button onclick="newChangelist()" title="New Changelist">+</button>
    </div>
    
    <!-- Changes Tree -->
    <div class="changes-area" id="changesArea">
        <div class="section" id="changesSection">
            <div class="section-header" onclick="toggleSection('changes')">
                <span class="arrow" id="changesArrow">▼</span>
                <span class="icon">📁</span>
                <span class="title">Changes</span>
                <span class="count" id="changesCount">0 files</span>
            </div>
            <div class="tree" id="changesTree"></div>
        </div>
        
        <div class="section" id="untrackedSection" style="display: none;">
            <div class="section-header" onclick="toggleSection('untracked')">
                <span class="arrow" id="untrackedArrow">▼</span>
                <span class="icon">📁</span>
                <span class="title">Unversioned Files</span>
                <span class="count" id="untrackedCount">0 files</span>
            </div>
            <div class="tree" id="untrackedTree"></div>
        </div>
    </div>
    
    <!-- Context Menu -->
    <div class="context-menu" id="contextMenu">
        <div class="context-menu-item" onclick="commitFile()">✓ Commit File...</div>
        <div class="context-menu-item" onclick="rollbackFile()">↩ Rollback...</div>
        <div class="context-menu-separator"></div>
        <div class="context-menu-item" onclick="moveToChangelist()">Move to Another Changelist...</div>
        <div class="context-menu-separator"></div>
        <div class="context-menu-item" onclick="showDiff()">⇋ Show Diff</div>
        <div class="context-menu-item" onclick="showDiffNewTab()">⇋ Show Diff in a New Tab</div>
        <div class="context-menu-separator"></div>
        <div class="context-menu-item" onclick="addToVcs()">+ Add to VCS</div>
        <div class="context-menu-separator"></div>
        <div class="context-menu-item" onclick="newChangelist()">+ New Changelist...</div>
        <div class="context-menu-item" onclick="editChangelist()">✎ Edit Changelist...</div>
        <div class="context-menu-separator"></div>
        <div class="context-menu-item" onclick="shelve()">📦 Shelve Changes...</div>
        <div class="context-menu-separator"></div>
        <div class="context-menu-item" onclick="refresh()">⟳ Refresh</div>
    </div>
    
    <!-- Commit Area -->
    <div class="commit-area">
        <div class="amend-row">
            <label>
                <input type="checkbox" id="amendCheck">
                Amend
            </label>
        </div>
        <textarea class="commit-message" id="commitMessage" placeholder="Commit Message"></textarea>
        <div class="commit-buttons">
            <button class="primary" onclick="commit()" id="commitBtn">Commit</button>
            <button class="secondary" onclick="commitAndPush()" id="commitPushBtn">Commit and Push...</button>
        </div>
    </div>
    
    <script>
        const vscode = acquireVsCodeApi();
        let currentData = { changes: [], untracked: [], selectedFiles: [] };
        let contextFilePath = null;
        let expandedSections = { changes: true, untracked: true };
        let expandedDirs = new Set();
        
        function refresh() {
            vscode.postMessage({ type: 'refresh' });
        }
        
        function toggleSection(section) {
            expandedSections[section] = !expandedSections[section];
            const arrow = document.getElementById(section + 'Arrow');
            const tree = document.getElementById(section + 'Tree');
            arrow.textContent = expandedSections[section] ? '▼' : '▶';
            tree.style.display = expandedSections[section] ? 'block' : 'none';
        }
        
        function renderTree(nodes, container, indent = 0) {
            container.innerHTML = '';
            nodes.forEach(node => {
                if (node.type === 'directory') {
                    const isExpanded = expandedDirs.has(node.path);
                    const fileCount = countFiles(node);
                    const div = document.createElement('div');
                    div.className = 'tree-item indent-' + Math.min(indent, 5);
                    div.innerHTML = \`
                        <span class="arrow" onclick="toggleDir('\${node.path}')">\${isExpanded ? '▼' : '▶'}</span>
                        <span class="icon">📁</span>
                        <span class="name">\${node.name} \${fileCount} files</span>
                    \`;
                    container.appendChild(div);
                    
                    if (isExpanded && node.children) {
                        const childContainer = document.createElement('div');
                        renderTree(node.children, childContainer, indent + 1);
                        container.appendChild(childContainer);
                    }
                } else {
                    const div = document.createElement('div');
                    div.className = 'tree-item indent-' + Math.min(indent, 5);
                    div.setAttribute('data-path', node.path);
                    div.oncontextmenu = (e) => showContextMenu(e, node.path);
                    div.ondblclick = () => showDiffFor(node.path);
                    div.innerHTML = \`
                        <input type="checkbox" \${node.checked ? 'checked' : ''} onclick="toggleFile('\${node.path}', this.checked)">
                        <span class="icon">\${getFileIcon(node.status)}</span>
                        <span class="name">\${node.name}</span>
                        <span class="status \${node.status}">\${node.status}</span>
                    \`;
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
        
        function getFileIcon(status) {
            switch(status) {
                case 'M': return '📝';
                case 'A': return '➕';
                case 'D': return '❌';
                case '?': return '❓';
                default: return '📄';
            }
        }
        
        function toggleDir(path) {
            if (expandedDirs.has(path)) {
                expandedDirs.delete(path);
            } else {
                expandedDirs.add(path);
            }
            update(currentData);
        }
        
        function toggleFile(path, checked) {
            vscode.postMessage({ type: 'toggleFile', path, checked });
        }
        
        function showContextMenu(e, path) {
            e.preventDefault();
            contextFilePath = path;
            const menu = document.getElementById('contextMenu');
            menu.style.left = e.clientX + 'px';
            menu.style.top = e.clientY + 'px';
            menu.classList.add('show');
        }
        
        document.addEventListener('click', () => {
            document.getElementById('contextMenu').classList.remove('show');
        });
        
        function commitFile() {
            if (contextFilePath) {
                const msg = prompt('Commit message for ' + contextFilePath);
                if (msg) {
                    vscode.postMessage({ type: 'commitFile', path: contextFilePath, message: msg });
                }
            }
        }
        
        function rollbackFile() {
            if (contextFilePath) {
                vscode.postMessage({ type: 'rollback', path: contextFilePath });
            }
        }
        
        function showDiff() {
            if (contextFilePath) {
                vscode.postMessage({ type: 'showDiff', path: contextFilePath });
            }
        }
        
        function showDiffFor(path) {
            vscode.postMessage({ type: 'showDiff', path: path });
        }
        
        function showDiffNewTab() {
            showDiff();
        }
        
        function addToVcs() {
            if (contextFilePath) {
                vscode.postMessage({ type: 'addToVcs', path: contextFilePath });
            }
        }
        
        function moveToChangelist() {
            if (contextFilePath) {
                vscode.postMessage({ type: 'moveToChangelist', path: contextFilePath });
            }
        }
        
        function newChangelist() {
            vscode.postMessage({ type: 'newChangelist' });
        }
        
        function editChangelist() {
            // TODO
        }
        
        function shelve() {
            alert('Shelve Changes - Coming soon');
        }
        
        function rollbackSelected() {
            // TODO: rollback selected files
        }
        
        function showDiffSelected() {
            // TODO: show diff for selected
        }
        
        function commit() {
            const message = document.getElementById('commitMessage').value;
            const amend = document.getElementById('amendCheck').checked;
            vscode.postMessage({ type: 'commit', message, amend });
        }
        
        function commitAndPush() {
            const message = document.getElementById('commitMessage').value;
            const amend = document.getElementById('amendCheck').checked;
            vscode.postMessage({ type: 'commitAndPush', message, amend });
        }
        
        function update(data) {
            currentData = data;
            
            // Expand all directories by default
            if (expandedDirs.size === 0) {
                collectDirs(data.changes);
                collectDirs(data.untracked);
            }
            
            const changesTree = document.getElementById('changesTree');
            const untrackedTree = document.getElementById('untrackedTree');
            
            const changesCount = countAllFiles(data.changes);
            const untrackedCount = countAllFiles(data.untracked);
            
            document.getElementById('changesCount').textContent = changesCount + ' files';
            document.getElementById('untrackedCount').textContent = untrackedCount + ' files';
            
            renderTree(data.changes, changesTree);
            renderTree(data.untracked, untrackedTree);
            
            document.getElementById('untrackedSection').style.display = untrackedCount > 0 ? 'block' : 'none';
            
            // Update commit button state
            document.getElementById('commitBtn').disabled = data.selectedFiles.length === 0;
            document.getElementById('commitPushBtn').disabled = data.selectedFiles.length === 0;
        }
        
        function countAllFiles(nodes) {
            let count = 0;
            nodes.forEach(n => {
                if (n.type === 'file') count++;
                if (n.children) count += countAllFiles(n.children);
            });
            return count;
        }
        
        function collectDirs(nodes) {
            nodes.forEach(n => {
                if (n.type === 'directory') {
                    expandedDirs.add(n.path);
                    if (n.children) collectDirs(n.children);
                }
            });
        }
        
        window.addEventListener('message', event => {
            const msg = event.data;
            if (msg.type === 'update') {
                update(msg);
            } else if (msg.type === 'commitComplete') {
                document.getElementById('commitMessage').value = '';
            }
        });
    </script>
</body>
</html>`;
    }
}
