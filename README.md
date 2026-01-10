# GitABC - Git Client for VS Code

A simple and intuitive Git client for Visual Studio Code, inspired by JetBrains Git integration. Works exclusively with GitHub repositories.

"/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code" --extensionDevelopmentPath=/Users/fatihalp/projects/gitabc

## Features

- **Source Control Integration**: View staged and unstaged changes in VS Code's Source Control panel
- **Branch Management**: Switch branches, create new branches from the status bar
- **Git Operations**: Commit, push, pull, and fetch with progress indicators
- **File Diff**: Click any changed file to see the diff
- **Stage/Unstage**: Stage or unstage individual files or all changes at once
- **Status Bar**: See current branch and ahead/behind status at a glance
- **Auto-fetch**: Automatically fetch updates from remote (configurable)

## Requirements

- Git must be installed and available in PATH
- Works only with GitHub repositories

## Commands

| Command | Description |
|---------|-------------|
| `GitABC: Commit` | Commit staged changes |
| `GitABC: Push` | Push commits to remote |
| `GitABC: Pull` | Pull changes from remote |
| `GitABC: Fetch` | Fetch updates from remote |
| `GitABC: Switch Branch` | Switch to another branch |
| `GitABC: Create Branch` | Create a new branch |
| `GitABC: Clone Repository` | Clone a GitHub repository |

## Usage

1. Open a folder containing a Git repository
2. The extension activates automatically if the remote is GitHub
3. Use the Source Control panel to see changes
4. Enter a commit message and click the checkmark to commit
5. Use the status bar to switch branches

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `gitabc.autoFetch` | `true` | Automatically fetch from remote |
| `gitabc.autoFetchInterval` | `180` | Auto-fetch interval in seconds |
| `gitabc.showStatusBarItem` | `true` | Show branch status in status bar |

## Development & Testing

### Prerequisites

```bash
# Node.js 18+ required
node --version

# Install dependencies
cd /Users/fatihalp/projects/gitabc
npm install
```

### Run in Development Mode

**Option 1: VS Code (Recommended - Easiest)**
1. Open this project in VS Code.
2. Press `F5` (or go to `Run and Debug` sidebar and click "Run Extension").
3. A new window [Extension Development Host] will open with GitABC active.

**Option 2: Terminal**
> [!TIP]
> If you get `command not found: code`, open VS Code, press `Cmd+Shift+P`, and run **"Shell Command: Install 'code' command in PATH"**.

```bash
# Compile the extension
npm run compile

# Launch VS Code with extension loaded
code --extensionDevelopmentPath=/Users/fatihalp/projects/gitabc
```

### Testing Steps

1. **Open a GitHub repository** in the new VS Code window
2. **Check Source Control panel** (Ctrl+Shift+G):
   - You should see "GitABC" as the SCM provider
   - Changed files appear in "Staged Changes" or "Changes" groups
3. **Check Status Bar**:
   - Current branch name should appear (bottom-left)
   - Ahead/behind indicators if applicable
4. **Test operations**:
   - Click a file to see diff
   - Stage/unstage files with +/- buttons
   - Enter commit message and click ✓
   - Use Push/Pull buttons

### Watch Mode (Auto-compile)

```bash
npm run watch
```

### Build for Production

```bash
npm run package
# Creates gitabc-1.0.0.vsix
```

## License

MIT
