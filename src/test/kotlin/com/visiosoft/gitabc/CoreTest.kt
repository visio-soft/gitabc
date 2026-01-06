package com.visiosoft.gitabc

import com.visiosoft.gitabc.git.GitService
import com.visiosoft.gitabc.model.ChangeStatus
import java.io.File

/**
 * Simple test to verify Git operations work correctly
 */
fun main() {
    val gitService = GitService()
    val testRepoPath = File("/tmp/test_repos")
    
    println("=== GitABC Core Functionality Test ===\n")
    
    // Test 1: Find repositories
    println("Test 1: Finding repositories...")
    val repositories = gitService.findRepositories(testRepoPath)
    println("Found ${repositories.size} repositories:")
    repositories.forEach { repo ->
        println("  - ${repo.name} at ${repo.path}")
    }
    println()
    
    // Test 2: Open repository and get branches
    if (repositories.isNotEmpty()) {
        val repo = repositories[0]
        println("Test 2: Opening repository ${repo.name}...")
        val git = gitService.openRepository(repo.path)
        
        if (git != null) {
            println("✓ Repository opened successfully")
            
            // Get branches
            val branches = gitService.getBranches(git)
            println("Branches (${branches.size}):")
            branches.forEach { branch ->
                val marker = if (branch.isCurrent) "* " else "  "
                println("$marker${branch.name}")
            }
            println()
            
            // Get commit status
            val commitStatus = gitService.getCommitStatus(git)
            println("Commit status:")
            println("  Ahead (unpushed): ${commitStatus.ahead}")
            println("  Behind (unpulled): ${commitStatus.behind}")
            println()
            
            // Get file changes
            val changes = gitService.getFileChanges(git)
            println("File changes (${changes.size}):")
            changes.forEach { change ->
                val statusSymbol = when (change.status) {
                    ChangeStatus.ADDED -> "[A]"
                    ChangeStatus.MODIFIED -> "[M]"
                    ChangeStatus.DELETED -> "[D]"
                    ChangeStatus.UNTRACKED -> "[?]"
                    ChangeStatus.CONFLICTING -> "[C]"
                }
                println("  $statusSymbol ${change.path} (in ${change.directory})")
            }
            println()
            
            git.close()
            println("✓ All tests passed!")
        } else {
            println("✗ Failed to open repository")
        }
    } else {
        println("✗ No repositories found")
    }
}
