package com.visiosoft.gitabc

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.visiosoft.gitabc.ui.*
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

@Composable
@Preview
fun App() {
    val appState = remember { AppState() }
    val scope = rememberCoroutineScope()
    var showDirectoryPicker by remember { mutableStateOf(false) }
    var showCreateChangelistDialog by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }
    
    // Auto-open last repository on startup
    LaunchedEffect(Unit) {
        if (appState.selectedRepository == null && appState.recentRepositories.isNotEmpty()) {
            appState.selectRepository(appState.recentRepositories[0], scope)
        }
    }
    
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
            
            // Error message
            appState.errorMessage?.let { error ->
                Surface(
                    color = Color(0xFFFFEBEE),
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("⚠️", modifier = Modifier.padding(top = 2.dp, end = 12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            InteractiveErrorMessage(error)
                        }
                        IconButton(
                            onClick = { appState.clearError() },
                            modifier = Modifier.size(24.dp).padding(start = 8.dp)
                        ) {
                            Text("✕", color = Color(0xFFC62828), fontSize = 14.sp)
                        }
                    }
                }
            }
            
            // Loading indicator
            if (appState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            
            // Main content
            Row(modifier = Modifier.fillMaxSize()) {
                // Repository list
                val allRepositories = (appState.repositories + appState.recentRepositories)
                    .distinctBy { it.path }
                
                RepositoryListPanel(
                    repositories = allRepositories,
                    selectedRepository = appState.selectedRepository,
                    onRepositorySelected = { repo ->
                        appState.selectRepository(repo, scope)
                    },
                    onRemoveRepository = { repo ->
                        appState.removeRepositoryFromRecent(repo)
                    },
                    onOpenFolder = { showDirectoryPicker = true },
                    onClone = { showCloneDialog = true }
                )
                
                VerticalDivider(color = Color(0xFFE5E5E5))
                
                // Main content area
                if (appState.selectedRepository != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top action bar: Branch + Commit
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            // Branch panel
                            Box(modifier = Modifier.weight(0.6f)) {
                                BranchPanel(
                                    branches = appState.branches,
                                    commitStatus = appState.commitStatus,
                                    onBranchSelected = { branchName ->
                                        appState.checkoutBranch(branchName, scope)
                                    },
                                    onPush = { appState.pushChanges(scope) },
                                    onFetch = { appState.fetchUpdates(scope) }
                                )
                            }
                            
                            VerticalDivider(color = Color(0xFFE5E5E5))
                            
                            // Commit panel
                            Box(modifier = Modifier.weight(0.4f)) {
                                CommitPanel(
                                    message = appState.commitMessage,
                                    onMessageChange = { appState.commitMessage = it },
                                    onCommit = { appState.commitChanges(appState.commitMessage, scope) },
                                    isEnabled = appState.fileChanges.isNotEmpty(),
                                    isLoading = appState.isPerformingAction,
                                    compact = true
                                )
                            }
                        }
                        
                        Divider(color = Color(0xFFE5E5E5))
                        
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Changelist panel
                            Box(modifier = Modifier.weight(0.4f)) {
                                ChangelistPanel(
                                    changelists = appState.changelists,
                                    selectedChangelist = appState.selectedChangelist,
                                    fileChanges = appState.fileChanges,
                                    hierarchicalChanges = appState.hierarchicalChanges,
                                    onChangelistSelected = { changelist ->
                                        appState.selectChangelist(changelist)
                                    },
                                    onCreateChangelist = {
                                        showCreateChangelistDialog = true
                                    },
                                    onMoveFile = { filePath, changelistId ->
                                        appState.moveFileToChangelist(filePath, changelistId, scope)
                                    },
                                    onFileClick = { filePath ->
                                        appState.loadDiff(filePath, scope)
                                    },
                                    draggingFile = appState.draggingFile,
                                    hoveredChangelistId = appState.hoveredChangelistId,
                                    onFileDragStart = { appState.startDragging(it) },
                                    onFileDragEnd = { appState.stopDragging(scope) },
                                    onChangelistHover = { appState.setHoveredChangelist(it) }
                                )
                            }
                            
                            VerticalDivider()
                            
                            // Diff viewer
                            Box(modifier = Modifier.weight(0.6f)) {
                                DiffViewer(appState.selectedFileDiff)
                            }
                        }
                    }
                } else {
                    // Welcome screen
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Welcome to GitABC",
                                style = MaterialTheme.typography.h4
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Open a folder to view Git repositories",
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { showDirectoryPicker = true }) {
                                Text("Open Folder")
                            }
                        }
                    }
                }
            }
        }
    }
    
    if (showCloneDialog) {
        CloneDialog(
            onDismiss = { showCloneDialog = false },
            onClone = { url, destination ->
                showCloneDialog = false
                appState.cloneRepository(url, destination, scope)
            }
        )
    }
    
    if (showDirectoryPicker) {
        LaunchedEffect(Unit) {
            val chooser = JFileChooser()
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.dialogTitle = "Select Directory"
            
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val selectedDir = chooser.selectedFile
                appState.loadRepositories(selectedDir, scope)
            }
            
            showDirectoryPicker = false
        }
    }
    
    // Create changelist dialog
    if (showCreateChangelistDialog) {
        CreateChangelistDialog(
            onDismiss = { showCreateChangelistDialog = false },
            onConfirm = { name ->
                appState.createChangelist(name)
                showCreateChangelistDialog = false
            }
        )
    }
}

@Composable
fun CreateChangelistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var changelistName by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Changelist") },
        text = {
            Column {
                Text("Enter changelist name:")
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = changelistName,
                    onValueChange = { changelistName = it },
                    placeholder = { Text("Changelist name") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (changelistName.isNotBlank()) {
                        onConfirm(changelistName.trim())
                    }
                },
                enabled = changelistName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun main() = application {
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "GitABC - Git Client",
        state = windowState
    ) {
        App()
    }
}

@Composable
fun VerticalDivider(color: Color = Color.LightGray) {
    Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = color)
}
