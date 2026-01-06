package com.visiosoft.gitabc

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            TopAppBar(
                title = { Text("GitABC - Git Client") },
                backgroundColor = Color(0xFF2196F3),
                contentColor = Color.White,
                actions = {
                    Button(
                        onClick = { showDirectoryPicker = true },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.White)
                    ) {
                        Text("Open Folder")
                    }
                    
                    if (appState.selectedRepository != null) {
                        Button(
                            onClick = { appState.refreshRepositoryData(scope) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color.White),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Refresh")
                        }
                    }
                }
            )
            
            // Error message
            appState.errorMessage?.let { error ->
                Surface(
                    color = Color(0xFFFFEBEE),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(error, color = Color(0xFFC62828))
                        TextButton(onClick = { appState.clearError() }) {
                            Text("✕")
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
                RepositoryListPanel(
                    repositories = appState.repositories,
                    selectedRepository = appState.selectedRepository,
                    onRepositorySelected = { repo ->
                        appState.selectRepository(repo, scope)
                    }
                )
                
                // Main content area
                if (appState.selectedRepository != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Branch panel
                        BranchPanel(
                            branches = appState.branches,
                            commitStatus = appState.commitStatus,
                            onBranchSelected = { branchName ->
                                appState.checkoutBranch(branchName, scope)
                            }
                        )
                        
                        Divider()
                        
                        // Changelist panel
                        ChangelistPanel(
                            changelists = appState.changelists,
                            selectedChangelist = appState.selectedChangelist,
                            fileChanges = appState.fileChanges,
                            onChangelistSelected = { changelist ->
                                appState.selectChangelist(changelist)
                            },
                            onCreateChangelist = {
                                showCreateChangelistDialog = true
                            },
                            onMoveFile = { filePath, changelistId ->
                                appState.moveFileToChangelist(filePath, changelistId, scope)
                            }
                        )
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
    
    // Directory picker dialog
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
