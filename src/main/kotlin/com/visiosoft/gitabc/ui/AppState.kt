package com.visiosoft.gitabc.ui

import androidx.compose.runtime.*
import com.visiosoft.gitabc.git.GitService
import com.visiosoft.gitabc.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File

/**
 * Application state manager
 */
class AppState {
    private val gitService = GitService()
    
    // Observable state
    var repositories by mutableStateOf<List<Repository>>(emptyList())
        private set
    
    var selectedRepository by mutableStateOf<Repository?>(null)
        private set
    
    var branches by mutableStateOf<List<Branch>>(emptyList())
        private set
    
    var commitStatus by mutableStateOf<CommitStatus>(CommitStatus())
        private set
    
    var fileChanges by mutableStateOf<List<FileChange>>(emptyList())
        private set
    
    var changelists by mutableStateOf<List<Changelist>>(listOf(Changelist.DEFAULT))
        private set
    
    var selectedChangelist by mutableStateOf<Changelist>(Changelist.DEFAULT)
        private set
    
    var isLoading by mutableStateOf(false)
        private set
    
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var selectedFileDiff by mutableStateOf<String?>(null)
        private set
    
    var recentRepositories by mutableStateOf<List<Repository>>(emptyList())
        private set

    var commitMessage by mutableStateOf("")
    
    var isPerformingAction by mutableStateOf(false)
        private set
    
    var hierarchicalChanges by mutableStateOf<List<ChangeNode>>(emptyList())
        private set
    
    var draggingFile by mutableStateOf<String?>(null)
        private set
    
    var hoveredChangelistId by mutableStateOf<String?>(null)
        private set

    private var currentGit: Git? = null

    init {
        loadRecentRepositories()
    }
    
    /**
     * Load repositories from a directory
     */
    fun loadRepositories(rootPath: File, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val found = gitService.findRepositories(rootPath)
                repositories = found
                errorMessage = null
                
                // Auto-select if exactly one repo found
                if (found.size == 1) {
                    selectRepository(found[0], scope)
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load repositories: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
    
    /**
     * Select a repository
     */
    fun selectRepository(repository: Repository, scope: CoroutineScope) {
        addToRecent(repository)
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                currentGit?.close()
                currentGit = gitService.openRepository(repository.path)
                
                if (currentGit != null) {
                    println("DEBUG: Successfully opened repository at: ${repository.path.absolutePath}")
                    withContext(Dispatchers.Main) {
                        // Reset transient state for the new repository
                        fileChanges = emptyList()
                        hierarchicalChanges = emptyList()
                        selectedRepository = repository
                        selectedChangelist = Changelist.DEFAULT
                        selectedFileDiff = null
                    }
                    refreshRepositoryData(scope)
                    errorMessage = null
                } else {
                    errorMessage = "Failed to open repository"
                }
            } catch (e: Exception) {
                errorMessage = "Error selecting repository: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
    
    /**
     * Refresh repository data
     */
    fun refreshRepositoryData(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            currentGit?.let { git ->
                try {
                    val newBranches = gitService.getBranches(git)
                    val newStatus = gitService.getCommitStatus(git)
                    val discoveredChanges = gitService.getFileChanges(git)
                    
                    println("DEBUG: Discovered ${discoveredChanges.size} file changes")
                    discoveredChanges.forEach { change ->
                        println("DEBUG: File: ${change.path}, Status: ${change.status}, Changelist: ${change.changelistId}")
                    }
                    
                    // Preserve existing changelist assignments
                    val updatedChanges = discoveredChanges.map { newChange ->
                        val existing = fileChanges.find { it.path == newChange.path }
                        if (existing != null) {
                            newChange.copy(changelistId = existing.changelistId)
                        } else {
                            newChange
                        }
                    }
                    
                    println("DEBUG: Updated changes count: ${updatedChanges.size}")
                    
                    withContext(Dispatchers.Main) {
                        branches = newBranches
                        commitStatus = newStatus
                        fileChanges = updatedChanges
                        hierarchicalChanges = buildHierarchy(updatedChanges)
                        println("DEBUG: Hierarchical changes count: ${hierarchicalChanges.size}")
                        println("DEBUG: File changes in state: ${fileChanges.size}")
                        selectedFileDiff = null // Reset diff when refreshing
                        errorMessage = null
                    }
                } catch (e: Exception) {
                    errorMessage = "Error refreshing data: ${e.message}"
                }
            }
        }
    }
    
    /**
     * Switch to a different branch
     */
    fun checkoutBranch(branchName: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                currentGit?.let { git ->
                    val success = gitService.checkoutBranch(git, branchName)
                    if (success) {
                        refreshRepositoryData(scope)
                        errorMessage = null
                    } else {
                        errorMessage = "Failed to checkout branch: $branchName"
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Error checking out branch: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
    
    /**
     * Create a new changelist
     */
    fun createChangelist(name: String) {
        val id = generateChangelistId(name)
        val newChangelist = Changelist(id, name, false)
        changelists = changelists + newChangelist
    }
    
    private fun generateChangelistId(name: String): String {
        // Generate a unique ID from the name
        val baseId = name.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        
        // Check for duplicates and append number if needed
        var id = baseId
        var counter = 1
        while (changelists.any { it.id == id }) {
            id = "${baseId}_$counter"
            counter++
        }
        return id
    }
    
    /**
     * Select a changelist
     */
    fun selectChangelist(changelist: Changelist) {
        selectedChangelist = changelist
    }
    
    /**
     * Move file to a different changelist
     */
    fun moveFileToChangelist(filePath: String, changelistId: String, scope: CoroutineScope) {
        val updatedList = fileChanges.map { change ->
            if (change.path == filePath) {
                change.copy(changelistId = changelistId)
            } else {
                change
            }
        }
        fileChanges = updatedList
        hierarchicalChanges = buildHierarchy(updatedList)
    }
    
    /**
     * Load the diff for a specific file
     */
    fun loadDiff(filePath: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            currentGit?.let { git ->
                selectedFileDiff = gitService.getFileDiff(git, filePath)
            }
        }
    }

    fun startDragging(filePath: String) {
        draggingFile = filePath
    }

    fun stopDragging(scope: CoroutineScope) {
        val file = draggingFile
        val target = hoveredChangelistId
        
        if (file != null && target != null) {
            moveFileToChangelist(file, target, scope)
        }
        
        draggingFile = null
        hoveredChangelistId = null
    }

    fun setHoveredChangelist(id: String?) {
        hoveredChangelistId = id
    }

    /**
     * Commit changes
     */
    fun commitChanges(message: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            currentGit?.let { git ->
                isPerformingAction = true
                try {
                    val success = gitService.commit(git, message)
                    if (success) {
                        commitMessage = ""
                        refreshRepositoryData(scope)
                        errorMessage = null
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Commit failed"
                } finally {
                    isPerformingAction = false
                }
            }
        }
    }

    /**
     * Push changes
     */
    fun pushChanges(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            currentGit?.let { git ->
                isPerformingAction = true
                try {
                    val success = gitService.push(git)
                    if (success) {
                        refreshRepositoryData(scope)
                        errorMessage = null
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Push failed"
                } finally {
                    isPerformingAction = false
                }
            }
        }
    }

    /**
     * Fetch updates
     */
    fun fetchUpdates(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            currentGit?.let { git ->
                isPerformingAction = true
                try {
                    val success = gitService.fetch(git)
                    if (success) {
                        refreshRepositoryData(scope)
                        errorMessage = null
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Fetch failed"
                } finally {
                    isPerformingAction = false
                }
            }
        }
    }

    private fun buildHierarchy(changes: List<FileChange>): List<ChangeNode> {
        val rootNodes = mutableListOf<ChangeNode>()
        
        changes.forEach { change ->
            val parts = change.path.split("/")
            var currentLevel = rootNodes
            var currentPath = ""
            
            parts.forEachIndexed { index, part ->
                currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                val isFile = index == parts.size - 1
                
                var node = currentLevel.find { it.name == part }
                if (node == null) {
                    node = if (isFile) {
                        ChangeNode(part, change.path, change)
                    } else {
                        ChangeNode(part, currentPath)
                    }
                    currentLevel.add(node)
                }
                currentLevel = node.children
            }
        }
        
        return rootNodes.sortedWith(compareBy({ it.isFile }, { it.name }))
    }

    private fun loadRecentRepositories() {
        try {
            val file = File(System.getProperty("user.home"), ".gitabc/recent.txt")
            if (file.exists()) {
                recentRepositories = file.readLines()
                    .filter { it.isNotBlank() }
                    .map { File(it) }
                    .filter { it.exists() && gitService.isGitRepository(it) }
                    .map { Repository(it) }
            }
        } catch (e: Exception) {
            // Silently fail
        }
    }

    fun cloneRepository(url: String, destination: File, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            errorMessage = null
            try {
                val success = gitService.cloneRepository(url, destination)
                if (success) {
                    val repo = Repository(destination)
                    selectRepository(repo, scope)
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Clone failed"
            } finally {
                isLoading = false
            }
        }
    }

    fun removeRepositoryFromRecent(repository: Repository) {
        recentRepositories = recentRepositories.filter { it.path.absolutePath != repository.path.absolutePath }
        saveRecentRepositories()
        if (selectedRepository?.path?.absolutePath == repository.path.absolutePath) {
            currentGit?.close()
            currentGit = null
            selectedRepository = null
        }
    }

    private fun addToRecent(repository: Repository) {
        val updated = (listOf(repository) + recentRepositories.filter { it.path.absolutePath != repository.path.absolutePath })
            .take(10)
        recentRepositories = updated
        saveRecentRepositories()
    }

    private fun saveRecentRepositories() {
        try {
            val dir = File(System.getProperty("user.home"), ".gitabc")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "recent.txt")
            file.writeText(recentRepositories.joinToString("\n") { it.path.absolutePath })
        } catch (e: Exception) {
            // Silently fail
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        errorMessage = null
    }
    
    /**
     * Close current repository
     */
    fun close() {
        currentGit?.close()
    }
}
