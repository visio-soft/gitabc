package com.visiosoft.gitabc.ui

import androidx.compose.runtime.*
import com.visiosoft.gitabc.git.GitService
import com.visiosoft.gitabc.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    
    private var currentGit: Git? = null
    
    /**
     * Load repositories from a directory
     */
    fun loadRepositories(rootPath: File, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                repositories = gitService.findRepositories(rootPath)
                errorMessage = null
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
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                currentGit?.close()
                currentGit = gitService.openRepository(repository.path)
                
                if (currentGit != null) {
                    selectedRepository = repository
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
                    branches = gitService.getBranches(git)
                    commitStatus = gitService.getCommitStatus(git)
                    fileChanges = gitService.getFileChanges(git)
                    errorMessage = null
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
        val id = name.lowercase().replace(" ", "_")
        val newChangelist = Changelist(id, name, false)
        changelists = changelists + newChangelist
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
        scope.launch(Dispatchers.IO) {
            fileChanges = fileChanges.map { change ->
                if (change.path == filePath) {
                    change.copy(changelistId = changelistId)
                } else {
                    change
                }
            }
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
