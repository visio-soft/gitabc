package com.visiosoft.gitabc.git

import com.visiosoft.gitabc.model.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.api.errors.TransportException
import java.io.ByteArrayOutputStream
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
            val repository = git.repository
            val currentBranch = repository.branch
            
            // Try to get tracking status
            val status = BranchTrackingStatus.of(repository, currentBranch)
            
            if (status != null) {
                CommitStatus(
                    ahead = status.aheadCount,
                    behind = status.behindCount
                )
            } else {
                // If status is null, it might be because there is no upstream
                // or we haven't fetched. 
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
            return 
        }
        
        path.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name != ".git") {
                findRepositoriesRecursive(file, maxDepth, currentDepth + 1, repositories)
            }
        }
    }
    
    /**
     * Get the diff for a specific file
     */
    fun getFileDiff(git: Git, filePath: String): String {
        return try {
            val repository = git.repository
            val status = git.status().addPath(filePath).call()
            
            // 1. Untracked file
            if (status.untracked.contains(filePath)) {
                val file = File(repository.workTree, filePath)
                if (file.exists()) {
                    val sb = StringBuilder()
                    file.forEachLine { line ->
                        sb.append("+ ").append(line).append("\n")
                    }
                    return sb.toString()
                }
                return "File not found"
            }

            // 2. Modified or Staged
            val out = ByteArrayOutputStream()
            val formatter = DiffFormatter(out)
            formatter.setRepository(repository)
            
            val headId = repository.resolve(Constants.HEAD)
            val headTree = if (headId != null) {
                val walk = RevWalk(repository)
                walk.parseTree(headId)
            } else {
                null
            }

            val oldTree = if (headTree != null) {
                CanonicalTreeParser(null, repository.newObjectReader(), headTree.id)
            } else {
                null
            }
            
            val newTree = FileTreeIterator(repository)
            
            formatter.setPathFilter(PathFilter.create(filePath))
            
            val entries = formatter.scan(oldTree, newTree)
            
            if (entries.isEmpty()) {
                return "No changes detected relative to HEAD for $filePath"
            }

            for (entry in entries) {
                formatter.format(entry)
            }
            
            out.toString()
        } catch (e: Exception) {
            "Error getting diff: ${e.message}"
        }
    }

    /**
     * Commit changes
     */
    fun commit(git: Git, message: String): Boolean {
        return try {
            // Add all changes to index (modified, deleted, and untracked)
            git.add().addFilepattern(".").call()
            
            val status = git.status().call()
            status.missing.forEach { path ->
                git.rm().addFilepattern(path).call()
            }
            
            git.commit().setMessage(message).call()
            true
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            throw Exception("Commit failed: $errorMsg")
        }
    }

    /**
     * Push changes to remote
     */
    fun push(git: Git): Boolean {
        return try {
            git.push().call()
            true
        } catch (e: TransportException) {
            val msg = e.message ?: ""
            val url = git.repository.config.getString("remote", "origin", "url") ?: ""
            if (url.contains("github.com")) {
                throw Exception(getGitHubErrorGuide(url, msg))
            }
            throw Exception("Push failed: $msg\n\nTry using SSH instead of HTTPS, or ensure your credentials are stored in git.")
        } catch (e: Exception) {
            throw Exception("Push failed: ${e.message}")
        }
    }

    /**
     * Fetch updates from remote
     */
    fun fetch(git: Git): Boolean {
        return try {
            git.fetch().call()
            true
        } catch (e: TransportException) {
            val msg = e.message ?: ""
            val url = git.repository.config.getString("remote", "origin", "url") ?: ""
            if (url.contains("github.com")) {
                throw Exception(getGitHubErrorGuide(url, msg))
            }
            throw Exception("Fetch failed: $msg\n\nSuggestions:\n1. Use SSH instead of HTTPS\n2. Run 'git fetch' in terminal once\n3. Check your internet connection")
        } catch (e: Exception) {
            throw Exception("Fetch failed: ${e.message}")
        }
    }

    /**
     * Clone a repository
     */
    fun cloneRepository(url: String, destination: File): Boolean {
        return try {
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(destination)
                .call()
                .close()
            true
        } catch (e: TransportException) {
            val msg = e.message ?: ""
            if (url.contains("github.com")) {
                throw Exception(getGitHubErrorGuide(url, msg))
            }
            throw Exception("Clone failed: $msg")
        } catch (e: Exception) {
            throw Exception("Clone failed: ${e.message}")
        }
    }

    private fun getGitHubErrorGuide(url: String, originalError: String): String {
        val isSSH = url.startsWith("git@") || url.startsWith("ssh://")
        
        return buildString {
            append("⚠️ GitHub Authentication Error\n")
            append("--------------------------------\n")
            if (isSSH) {
                append("You're using SSH. Follow these steps to set up your SSH key:\n\n")
                append("STEP 1: Check if you already have an SSH key\n")
                append("   [CMD]ls -la ~/.ssh[/CMD]\n")
                append("   Look for files like 'id_ed25519.pub' or 'id_rsa.pub'\n\n")
                
                append("STEP 2: If you DON'T have a key, create one:\n")
                append("   [CMD]ssh-keygen -t ed25519 -C \"your_email@example.com\" -N \"\" -f ~/.ssh/id_ed25519[/CMD]\n")
                append("   This creates a key with no passphrase in the default location\n\n")
                
                append("STEP 3: Copy your public key:\n")
                append("   [CMD]cat ~/.ssh/id_ed25519.pub[/CMD]\n")
                append("   Copy the entire output (starts with 'ssh-ed25519')\n\n")
                
                append("STEP 4: Add the key to GitHub:\n")
                append("   [LINK]https://github.com/settings/keys[/LINK]\n")
                append("   Click 'New SSH key', paste your key, and save\n\n")
                
                append("STEP 5: Test your connection:\n")
                append("   [CMD]ssh -T git@github.com[/CMD]\n")
                append("   You should see: 'Hi username! You've successfully authenticated'\n\n")
                
                append("STEP 6: If still not working, add key to SSH agent:\n")
                append("   [CMD]ssh-add ~/.ssh/id_ed25519[/CMD]\n")
            } else {
                append("You're using HTTPS. Follow these steps:\n\n")
                append("STEP 1: Create a Personal Access Token:\n")
                append("   [LINK]https://github.com/settings/tokens[/LINK]\n")
                append("   Click 'Generate new token (classic)'\n")
                append("   Select 'repo' scope and click 'Generate token'\n")
                append("   COPY THE TOKEN (you won't see it again!)\n\n")
                
                append("STEP 2: Clone using the token:\n")
                append("   [CMD]git clone $url[/CMD]\n")
                append("   Username: your_github_username\n")
                append("   Password: PASTE YOUR TOKEN (not your GitHub password!)\n\n")
                
                append("STEP 3: After successful clone, GitABC will work automatically\n")
            }
            append("\nOriginal Error: $originalError")
        }
    }
}
