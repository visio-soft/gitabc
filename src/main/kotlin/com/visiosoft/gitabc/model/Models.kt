package com.visiosoft.gitabc.model

import java.io.File

/**
 * Represents a Git repository
 */
data class Repository(
    val path: File,
    val name: String = path.name
) {
    val displayPath: String = path.absolutePath
}

/**
 * Represents a Git branch
 */
data class Branch(
    val name: String,
    val isCurrent: Boolean = false,
    val isRemote: Boolean = false
)

/**
 * Represents commit count status
 */
data class CommitStatus(
    val ahead: Int = 0,  // Unpushed commits
    val behind: Int = 0  // Unpulled commits
)

/**
 * Represents a file change
 */
data class FileChange(
    val path: String,
    val status: ChangeStatus,
    val changelistId: String = "Default"
) {
    val directory: String = File(path).parent ?: "/"
    val fileName: String = File(path).name
}

/**
 * File change status types
 */
enum class ChangeStatus {
    ADDED,
    MODIFIED,
    DELETED,
    UNTRACKED,
    CONFLICTING
}

/**
 * Represents a changelist for organizing changes
 */
data class Changelist(
    val id: String,
    val name: String,
    val isDefault: Boolean = false
) {
    companion object {
        val DEFAULT = Changelist("default", "Default", true)
    }
}
