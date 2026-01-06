# GitABC

A multiplatform Git client for macOS and Linux built with Kotlin and Compose Multiplatform.

## Features

- **Multi-repository support**: Browse and manage multiple Git repositories in a single view
- **Directory-based change tracking**: View file changes organized by directory
- **Branch management**: Easy switching between branches with visual indicators
- **Commit status tracking**: See unpulled and unpushed commit counts at a glance
- **Changelist support**: Organize your changes with custom changelists (similar to JetBrains IDEs)
- **Clean, intuitive UI**: Simple and efficient interface inspired by JetBrains Git integration

## Requirements

- Java 11 or higher
- macOS or Linux operating system

## Building

Build the project using Gradle:

```bash
./gradlew build
```

## Running

Run the application directly:

```bash
./gradlew run
```

## Packaging

Create native distributions:

### For macOS (DMG):
```bash
./gradlew packageDmg
```

### For Linux (DEB):
```bash
./gradlew packageDeb
```

The distribution packages will be created in `build/compose/binaries/main/` directory.

## Usage

1. **Open a folder**: Click the "Open Folder" button in the top bar to select a directory. GitABC will automatically find all Git repositories within that directory.

2. **Select a repository**: Click on a repository in the left panel to view its details.

3. **Switch branches**: Use the branch dropdown in the top panel to switch between branches. The current branch is highlighted.

4. **View commit status**: The top panel shows the number of unpulled (↓) and unpushed (↑) commits.

5. **Organize changes with changelists**:
   - File changes are automatically added to the "Default" changelist
   - Click the "+" button to create new changelists
   - Click on a file and select a different changelist from the context menu to move it

6. **View changes by directory**: Changes are grouped by their parent directory for better organization.

7. **Refresh**: Click the "Refresh" button to reload repository data after making changes outside the application.

## File Status Indicators

- **A** (Green): Added files
- **M** (Blue): Modified files
- **D** (Red): Deleted files
- **?** (Gray): Untracked files
- **C** (Orange): Conflicting files

## Technology Stack

- **Kotlin**: Modern, concise programming language
- **Compose Multiplatform**: Declarative UI framework
- **JGit**: Pure Java implementation of Git
- **Gradle**: Build and dependency management

## Project Structure

```
src/main/kotlin/com/visiosoft/gitabc/
├── model/          # Data models (Repository, Branch, FileChange, etc.)
├── git/            # Git operations using JGit
├── ui/             # UI components and state management
└── Main.kt         # Application entry point
```

## License

This project is open source and available under the MIT License.
