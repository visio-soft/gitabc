package com.visiosoft.gitabc.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visiosoft.gitabc.model.*

/**
 * Repository list panel
 */
@Composable
fun RepositoryListPanel(
    repositories: List<Repository>,
    selectedRepository: Repository?,
    onRepositorySelected: (Repository) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxHeight().width(250.dp).background(Color(0xFFF5F5F5))) {
        Text(
            "Repositories",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )
        Divider()
        
        LazyColumn {
            items(repositories) { repo ->
                RepositoryItem(
                    repository = repo,
                    isSelected = repo == selectedRepository,
                    onClick = { onRepositorySelected(repo) }
                )
            }
        }
    }
}

@Composable
fun RepositoryItem(
    repository: Repository,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) Color(0xFFE3F2FD) else Color.Transparent
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                repository.name,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )
            Text(
                repository.displayPath,
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Branch panel
 */
@Composable
fun BranchPanel(
    branches: List<Branch>,
    commitStatus: CommitStatus,
    onBranchSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().background(Color(0xFFFAFAFA)).padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Branch: ", fontSize = 13.sp, color = Color.Gray)
                
                val currentBranch = branches.firstOrNull { it.isCurrent }
                if (currentBranch != null) {
                    BranchDropdown(
                        branches = branches,
                        currentBranch = currentBranch,
                        onBranchSelected = onBranchSelected
                    )
                } else {
                    Text("No branch", fontSize = 13.sp)
                }
            }
            
            // Commit status
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (commitStatus.ahead > 0) {
                    StatusBadge("↑ ${commitStatus.ahead} unpushed", Color(0xFF4CAF50))
                }
                if (commitStatus.behind > 0) {
                    StatusBadge("↓ ${commitStatus.behind} unpulled", Color(0xFFFF9800))
                }
            }
        }
    }
}

@Composable
fun BranchDropdown(
    branches: List<Branch>,
    currentBranch: Branch,
    onBranchSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box {
        Button(
            onClick = { expanded = true },
            colors = ButtonDefaults.buttonColors(backgroundColor = Color.White),
            elevation = ButtonDefaults.elevation(0.dp)
        ) {
            Text(currentBranch.name, fontSize = 13.sp)
            Text(" ▼", fontSize = 10.sp)
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            branches.forEach { branch ->
                DropdownMenuItem(
                    onClick = {
                        onBranchSelected(branch.name)
                        expanded = false
                    }
                ) {
                    Text(
                        branch.name,
                        fontWeight = if (branch.isCurrent) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            color = color,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Changelist panel
 */
@Composable
fun ChangelistPanel(
    changelists: List<Changelist>,
    selectedChangelist: Changelist,
    fileChanges: List<FileChange>,
    onChangelistSelected: (Changelist) -> Unit,
    onCreateChangelist: () -> Unit,
    onMoveFile: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Changelist tabs
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                changelists.forEach { changelist ->
                    ChangelistTab(
                        changelist = changelist,
                        isSelected = changelist == selectedChangelist,
                        fileCount = fileChanges.count { it.changelistId == changelist.id },
                        onClick = { onChangelistSelected(changelist) }
                    )
                }
            }
            
            Button(
                onClick = onCreateChangelist,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color.White),
                modifier = Modifier.height(32.dp)
            ) {
                Text("+", fontSize = 16.sp)
            }
        }
        
        Divider()
        
        // File changes grouped by directory
        val changesInSelectedList = fileChanges.filter { it.changelistId == selectedChangelist.id }
        val groupedChanges = changesInSelectedList.groupBy { it.directory }
        
        LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            groupedChanges.forEach { (directory, changes) ->
                item {
                    DirectoryGroup(
                        directory = directory,
                        changes = changes,
                        changelists = changelists,
                        onMoveFile = onMoveFile
                    )
                }
            }
            
            if (changesInSelectedList.isEmpty()) {
                item {
                    Text(
                        "No changes in this changelist",
                        modifier = Modifier.padding(16.dp),
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ChangelistTab(
    changelist: Changelist,
    isSelected: Boolean,
    fileCount: Int,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) Color.White else Color.Transparent
    val textColor = if (isSelected) Color.Black else Color.Gray
    
    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                changelist.name,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
            if (fileCount > 0) {
                Text(
                    "($fileCount)",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun DirectoryGroup(
    directory: String,
    changes: List<FileChange>,
    changelists: List<Changelist>,
    onMoveFile: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // Directory header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (expanded) "▼" else "▶", fontSize = 12.sp)
            Text(
                directory,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                "(${changes.size})",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
        
        // Files
        if (expanded) {
            changes.forEach { change ->
                FileChangeItem(
                    change = change,
                    changelists = changelists,
                    onMoveFile = onMoveFile
                )
            }
        }
    }
}

@Composable
fun FileChangeItem(
    change: FileChange,
    changelists: List<Changelist>,
    onMoveFile: (String, String) -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 2.dp, bottom = 2.dp, end = 8.dp)
            .clickable { showContextMenu = true },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileStatusIcon(change.status)
            Text(
                change.fileName,
                fontSize = 13.sp
            )
        }
        
        if (showContextMenu && changelists.size > 1) {
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                Text(
                    "Move to changelist:",
                    modifier = Modifier.padding(8.dp),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                changelists.forEach { changelist ->
                    DropdownMenuItem(
                        onClick = {
                            onMoveFile(change.path, changelist.id)
                            showContextMenu = false
                        }
                    ) {
                        Text(changelist.name, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun FileStatusIcon(status: ChangeStatus) {
    val (text, color) = when (status) {
        ChangeStatus.ADDED -> "A" to Color(0xFF4CAF50)
        ChangeStatus.MODIFIED -> "M" to Color(0xFF2196F3)
        ChangeStatus.DELETED -> "D" to Color(0xFFF44336)
        ChangeStatus.UNTRACKED -> "?" to Color(0xFF9E9E9E)
        ChangeStatus.CONFLICTING -> "C" to Color(0xFFFF9800)
    }
    
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(18.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
