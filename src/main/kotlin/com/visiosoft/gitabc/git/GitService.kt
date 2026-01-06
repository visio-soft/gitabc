package com.visiosoft.gitabc.git

import com.visiosoft.gitabc.model.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import java.io.File

/**
 * Service for Git operations using JGit
 */
class GitService {
    
    /**
     * Open a Git repository
     */
    fun openRepository(path: File): Git? {
        return try {
            val builder = FileRepositoryBuilder()
            val repository = builder
                .setGitDir(File(path, ".git"))
                .readEnvironment()
                .findGitDir(path)
                .build()
            Git(repository)
        } catch (e: Exception) {
            // Log error - in a production app, use proper logging
            System.err.println("Failed to open repository at ${path.absolutePath}: ${e.message}")
            null
        }
    }
    
    /**
     * Get all branches in the repository
     */
    fun getBranches(git: Git): List<Branch> {
        return try {
            val branches = mutableListOf<Branch>()
            val currentBranch = git.repository.fullBranch
            
            // Local branches
            git.branchList().call().forEach { ref ->
                branches.add(
                    Branch(
                        name = ref.name.removePrefix("refs/heads/"),
                        isCurrent = ref.name == currentBranch,
                        isRemote = false
                    )
                )
            }
            
            branches
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Switch to a different branch
     */
    fun checkoutBranch(git: Git, branchName: String): Boolean {
        return try {
            git.checkout().setName(branchName).call()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get commit status (ahead/behind)
     */
    fun getCommitStatus(git: Git): CommitStatus {
        return try {
            val currentBranch = git.repository.branch
            val status = BranchTrackingStatus.of(git.repository, currentBranch)
            
            if (status != null) {
                CommitStatus(
                    ahead = status.aheadCount,
                    behind = status.behindCount
                )
            } else {
                CommitStatus()
            }
        } catch (e: Exception) {
            CommitStatus()
        }
    }
    
    /**
     * Get file changes in the repository
     */
    fun getFileChanges(git: Git): List<FileChange> {
        return try {
            val changes = mutableListOf<FileChange>()
            val status = git.status().call()
            
            // Added files
            status.added.forEach { path ->
                changes.add(FileChange(path, ChangeStatus.ADDED))
            }
            
            // Modified files
            status.modified.forEach { path ->
                changes.add(FileChange(path, ChangeStatus.MODIFIED))
            }
            
            // Changed files (in index)
            status.changed.forEach { path ->
                changes.add(FileChange(path, ChangeStatus.MODIFIED))
            }
            
            // Deleted files
            status.removed.forEach { path ->
                changes.add(FileChange(path, ChangeStatus.DELETED))
            }
            
            status.missing.forEach { path ->
                changes.add(FileChange(path, ChangeStatus.DELETED))
            }
            
            // Untracked files
            status.untracked.forEach { path ->
                changes.add(FileChange(path, ChangeStatus.UNTRACKED))
            }
            
            // Conflicting files
            status.conflicting.forEach { path ->
                changes.add(FileChange(path, ChangeStatus.CONFLICTING))
            }
            
            changes
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Check if a directory is a Git repository
     */
    fun isGitRepository(path: File): Boolean {
        return File(path, ".git").exists()
    }
    
    /**
     * Find all Git repositories in a directory (recursive)
     */
    fun findRepositories(rootPath: File, maxDepth: Int = 3): List<Repository> {
        val repositories = mutableListOf<Repository>()
        findRepositoriesRecursive(rootPath, maxDepth, 0, repositories)
        return repositories
    }
    
    private fun findRepositoriesRecursive(
        path: File,
        maxDepth: Int,
        currentDepth: Int,
        repositories: MutableList<Repository>
    ) {
        if (currentDepth > maxDepth || !path.isDirectory) {
            return
        }
        
        if (isGitRepository(path)) {
            repositories.add(Repository(path))
            return // Don't search inside git repositories
        }
        
        path.listFiles()?.forEach { file ->
            // Skip .git directories but allow other hidden directories
            if (file.isDirectory && file.name != ".git") {
                findRepositoriesRecursive(file, maxDepth, currentDepth + 1, repositories)
            }
        }
    }
}
