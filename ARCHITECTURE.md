# GitABC Architecture

## Overview

GitABC is a desktop Git client built with Kotlin and Compose Multiplatform. It provides a simple, intuitive interface for managing Git repositories across macOS and Linux platforms.

## Architecture Layers

### 1. Model Layer (`model/`)

Contains the core domain models:

- **Repository**: Represents a Git repository with its path and metadata
- **Branch**: Represents a Git branch with current status indicator
- **FileChange**: Represents a file change with its status and associated changelist
- **Changelist**: Represents a collection of related changes
- **CommitStatus**: Tracks ahead/behind commit counts

### 2. Git Service Layer (`git/`)

**GitService**: Wrapper around JGit that provides:

- Repository discovery and opening
- Branch listing and checkout
- Commit status tracking (ahead/behind)
- File change detection
- Working tree status monitoring

Key methods:
```kotlin
- openRepository(path: File): Git?
- getBranches(git: Git): List<Branch>
- checkoutBranch(git: Git, branchName: String): Boolean
- getCommitStatus(git: Git): CommitStatus
- getFileChanges(git: Git): List<FileChange>
- findRepositories(rootPath: File): List<Repository>
```

### 3. UI Layer (`ui/`)

#### AppState
Central state management using Compose's mutableStateOf:
- Manages repository selection
- Tracks file changes
- Handles changelist management
- Coordinates async Git operations using coroutines

#### Components
Reusable UI components:
- **RepositoryListPanel**: Left sidebar showing available repositories
- **BranchPanel**: Top bar with branch selector and commit status
- **ChangelistPanel**: Main area showing changelists and file changes
- **DirectoryGroup**: Collapsible directory view of changes
- **FileChangeItem**: Individual file change with status indicator

### 4. Main Application

**Main.kt**: Application entry point
- Window configuration
- Top-level UI composition
- Directory picker integration
- Dialog management

## Data Flow

```
User Action → AppState Method → GitService Operation → JGit API
                ↓
         State Update (mutableStateOf)
                ↓
         UI Recomposition
```

## Concurrency Model

- UI runs on the main thread
- Git operations run on IO dispatcher using coroutines
- State updates are thread-safe via Compose's state mechanism

## Key Design Decisions

1. **JGit over Native Git**: Pure Java implementation for better multiplatform support
2. **Compose Multiplatform**: Single codebase for desktop UIs
3. **Coroutines**: Async operations without blocking the UI
4. **Immutable State**: State changes trigger automatic UI updates
5. **Directory-based Grouping**: Natural organization of file changes

## Extension Points

To extend functionality:

1. **Add new Git operations**: Extend GitService
2. **Add UI features**: Create new components in `ui/Components.kt`
3. **Add state**: Extend AppState with new mutableStateOf properties
4. **Add models**: Create new data classes in `model/Models.kt`

## Testing Strategy

- Unit tests for GitService operations
- Integration tests with test repositories
- Manual UI testing for user interactions

## Dependencies

- **JGit 6.8.0**: Git operations
- **Compose Desktop 1.5.11**: UI framework
- **Kotlin 1.9.21**: Programming language
- **Kotlinx Coroutines**: Async operations
