package com.visiosoft.gitabc.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visiosoft.gitabc.model.*

/**
 * Interactive error message that parses and renders clickable links and executable commands
 */
@Composable
fun InteractiveErrorMessage(message: String) {
    var commandOutput by remember { mutableStateOf<String?>(null) }
    var runningCommand by remember { mutableStateOf(false) }
    
    val parts = parseErrorMessage(message)
    
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEach { part ->
            when (part) {
                is ErrorPart.Text -> {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            part.content,
                            color = Color(0xFFC62828),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontFamily = if (message.contains("GitHub Authentication Error")) FontFamily.Monospace else FontFamily.Default
                        )
                    }
                }
                is ErrorPart.Link -> {
                    Text(
                        part.url,
                        color = Color(0xFF0071E3),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable {
                                try {
                                    Desktop.getDesktop().browse(URI(part.url))
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                        style = androidx.compose.ui.text.TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                    )
                }
                is ErrorPart.Command -> {
                    Button(
                        onClick = {
                            runningCommand = true
                            Thread {
                                try {
                                    val process = ProcessBuilder("sh", "-c", part.command)
                                        .redirectErrorStream(true)
                                        .start()
                                    val output = process.inputStream.bufferedReader().readText()
                                    commandOutput = "$ ${part.command}\n$output"
                                } catch (e: Exception) {
                                    commandOutput = "Error: ${e.message}"
                                } finally {
                                    runningCommand = false
                                }
                            }.start()
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFFF2F2F7)
                        ),
                        elevation = ButtonDefaults.elevation(0.dp, 0.dp),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.height(32.dp),
                        enabled = !runningCommand
                    ) {
                        if (runningCommand) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color(0xFF1D1D1F),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            "▶ ${part.command}",
                            fontSize = 12.sp,
                            color = Color(0xFF1D1D1F),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        
        // Show command output if available
        commandOutput?.let { output ->
            Surface(
                color = Color(0xFF1D1D1F),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Terminal Output:",
                            color = Color(0xFF34C759),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Copy button
                            IconButton(
                                onClick = {
                                    try {
                                        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                        val selection = java.awt.datatransfer.StringSelection(output)
                                        clipboard.setContents(selection, selection)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Text("📋", fontSize = 12.sp)
                            }
                            // Close button
                            IconButton(
                                onClick = { commandOutput = null },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Text("✕", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            output,
                            color = Color(0xFFE5E5E5),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

sealed class ErrorPart {
    data class Text(val content: String) : ErrorPart()
    data class Link(val url: String) : ErrorPart()
    data class Command(val command: String) : ErrorPart()
}

fun parseErrorMessage(message: String): List<ErrorPart> {
    val parts = mutableListOf<ErrorPart>()
    var currentText = StringBuilder()
    var i = 0
    
    while (i < message.length) {
        when {
            message.startsWith("[LINK]", i) -> {
                if (currentText.isNotEmpty()) {
                    parts.add(ErrorPart.Text(currentText.toString()))
                    currentText = StringBuilder()
                }
                val endIndex = message.indexOf("[/LINK]", i)
                if (endIndex != -1) {
                    val url = message.substring(i + 6, endIndex)
                    parts.add(ErrorPart.Link(url))
                    i = endIndex + 7
                } else {
                    currentText.append(message[i])
                    i++
                }
            }
            message.startsWith("[CMD]", i) -> {
                if (currentText.isNotEmpty()) {
                    parts.add(ErrorPart.Text(currentText.toString()))
                    currentText = StringBuilder()
                }
                val endIndex = message.indexOf("[/CMD]", i)
                if (endIndex != -1) {
                    val command = message.substring(i + 5, endIndex)
                    parts.add(ErrorPart.Command(command))
                    i = endIndex + 6
                } else {
                    currentText.append(message[i])
                    i++
                }
            }
            else -> {
                currentText.append(message[i])
                i++
            }
        }
    }
    
    if (currentText.isNotEmpty()) {
        parts.add(ErrorPart.Text(currentText.toString()))
    }
    
    return parts
}

/**
 * Repository list panel
 */
@Composable
fun RepositoryListPanel(
    repositories: List<Repository>,
    selectedRepository: Repository?,
    onRepositorySelected: (Repository) -> Unit,
    onRemoveRepository: (Repository) -> Unit,
    onOpenFolder: () -> Unit,
    onClone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxHeight().width(260.dp).background(Color(0xFFFBFBFB))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Repositories",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D1D1F)
            )
            
            IconButton(
                onClick = onOpenFolder,
                modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
            ) {
                Text("📂", fontSize = 18.sp)
            }
            
            IconButton(
                onClick = onClone,
                modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
            ) {
                Text("🔗", fontSize = 18.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            items(repositories) { repo ->
                RepositoryItem(
                    repository = repo,
                    isSelected = repo.path == selectedRepository?.path,
                    onClick = { onRepositorySelected(repo) },
                    onRemove = { onRemoveRepository(repo) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            
            if (repositories.isEmpty()) {
                item {
                    Text(
                        "No repositories found",
                        modifier = Modifier.padding(16.dp),
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RepositoryItem(
    repository: Repository,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    
    Surface(
        color = if (isSelected) Color(0xFF0071E3) else if (isHovered) Color(0xFFF2F2F7) else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                // Simplified hover
            }
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    repository.name,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 14.sp,
                    color = if (isSelected) Color.White else Color(0xFF1D1D1F)
                )
                Text(
                    repository.displayPath,
                    fontSize = 11.sp,
                    color = if (isSelected) Color.White.copy(alpha = 0.8f) else Color.Gray,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1
                )
            }
            
            if (isHovered || isSelected) {
                IconButton(
                    onClick = { onRemove() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        "✕", 
                        fontSize = 12.sp, 
                        color = if (isSelected) Color.White.copy(alpha = 0.7f) else Color.Gray
                    )
                }
            }
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
    onPush: () -> Unit,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        elevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Branch selector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Branch:", color = Color.Gray, fontSize = 13.sp)
                    val currentBranch = branches.find { it.isCurrent }
                    if (currentBranch != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        BranchDropdown(
                            branches = branches,
                            currentBranch = currentBranch,
                            onBranchSelected = onBranchSelected
                        )
                    }
                }
                
                // Actions
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (commitStatus.behind > 0) {
                        StatusBadge("↓ ${commitStatus.behind} pull", Color(0xFF0071E3))
                    }
                    if (commitStatus.ahead > 0) {
                        StatusBadge("↑ ${commitStatus.ahead} push", Color(0xFF34C759))
                    }
                    
                    Button(
                        onClick = onFetch,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF2F2F7)),
                        elevation = ButtonDefaults.elevation(0.dp, 0.dp),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Fetch", fontSize = 13.sp, color = Color(0xFF1D1D1F))
                    }
                    
                    Button(
                        onClick = onPush,
                        enabled = commitStatus.ahead > 0,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF0071E3),
                            disabledBackgroundColor = Color(0xFFF2F2F7)
                        ),
                        elevation = ButtonDefaults.elevation(0.dp, 0.dp),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Push", fontSize = 13.sp, color = if (commitStatus.ahead > 0) Color.White else Color.Gray)
                    }
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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChangelistPanel(
    changelists: List<Changelist>,
    selectedChangelist: Changelist,
    fileChanges: List<FileChange>,
    hierarchicalChanges: List<ChangeNode>,
    onChangelistSelected: (Changelist) -> Unit,
    onCreateChangelist: () -> Unit,
    onMoveFile: (String, String) -> Unit,
    onFileClick: (String) -> Unit,
    draggingFile: String?,
    hoveredChangelistId: String?,
    onFileDragStart: (String) -> Unit,
    onFileDragEnd: () -> Unit,
    onChangelistHover: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var tabsPositions by remember { mutableStateOf(mapOf<String, androidx.compose.ui.geometry.Rect>()) }
    
    Column(modifier = modifier.fillMaxSize().background(Color.White)) {
        // Changelist tabs container with global hover detection during drag
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF9F9F9))
                .padding(8.dp)
                .onPointerEvent(PointerEventType.Move) { event ->
                    if (draggingFile != null) {
                        val position = event.changes.first().position
                        val hoveredTab = tabsPositions.entries.find { it.value.contains(position) }?.key
                        onChangelistHover(hoveredTab)
                    }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                changelists.forEach { changelist ->
                    ChangelistTab(
                        changelist = changelist,
                        isSelected = changelist == selectedChangelist,
                        isHovered = hoveredChangelistId == changelist.id,
                        fileCount = fileChanges.count { it.changelistId == changelist.id },
                        onClick = { onChangelistSelected(changelist) },
                        onPositioned = { rect ->
                            tabsPositions = tabsPositions + (changelist.id to rect)
                        }
                    )
                }
            }
            
            IconButton(
                onClick = onCreateChangelist,
                modifier = Modifier.size(24.dp)
            ) {
                Text("+", fontSize = 20.sp, color = Color(0xFF0071E3))
            }
        }
        
        Divider(color = Color(0xFFE5E5E5))
        
        // Hierarchical tree view
        val changesInSelectedList = fileChanges.filter { it.changelistId == selectedChangelist.id }
        
        println("DEBUG ChangelistPanel: Total file changes: ${fileChanges.size}")
        println("DEBUG ChangelistPanel: Selected changelist: ${selectedChangelist.id}")
        println("DEBUG ChangelistPanel: Changes in selected list: ${changesInSelectedList.size}")
        fileChanges.forEach { change ->
            println("DEBUG ChangelistPanel: File ${change.path} has changelistId: ${change.changelistId}")
        }
        
        // Filter the pre-built hierarchy based on the selected changelist
        val filteredHierarchy = hierarchicalChanges.filter { node ->
            (node.isFile && node.fileChange?.changelistId == selectedChangelist.id) ||
            (node.isDirectory && hasChangesInChangelist(node, selectedChangelist.id))
        }
        
        println("DEBUG ChangelistPanel: Filtered hierarchy size: ${filteredHierarchy.size}")

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(filteredHierarchy) { node ->
                    ChangeTreeItem(
                        node = node,
                        changelistId = selectedChangelist.id,
                        changelists = changelists,
                        onMoveFile = onMoveFile,
                        onFileClick = onFileClick,
                        onDragStart = onFileDragStart,
                        onDragEnd = onFileDragEnd,
                        draggingFile = draggingFile,
                        depth = 0
                    )
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
}

private fun hasChangesInChangelist(node: ChangeNode, changelistId: String): Boolean {
    return node.children.any { child ->
        (child.isFile && child.fileChange?.changelistId == changelistId) ||
        (child.isDirectory && hasChangesInChangelist(child, changelistId))
    }
}

@Composable
fun ChangeTreeItem(
    node: ChangeNode,
    changelistId: String,
    changelists: List<Changelist>,
    onMoveFile: (String, String) -> Unit,
    onFileClick: (String) -> Unit,
    onDragStart: (String) -> Unit,
    onDragEnd: () -> Unit,
    draggingFile: String?,
    depth: Int
) {
    var expanded by remember { mutableStateOf(node.isExpanded) }
    
    // Filter out children that don't belong to this changelist
    val relevantChildren = node.children.filter { child ->
        child.isFile && child.fileChange?.changelistId == changelistId ||
        child.isDirectory && hasChangesInChangelist(child, changelistId)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (node.isDirectory) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp, horizontal = (depth * 16).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(if (expanded) "▼" else "▶", fontSize = 10.sp)
                Text(
                    node.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            
            if (expanded) {
                relevantChildren.forEach { child ->
                    ChangeTreeItem(
                        node = child,
                        changelistId = changelistId,
                        changelists = changelists,
                        onMoveFile = onMoveFile,
                        onFileClick = onFileClick,
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd,
                        draggingFile = draggingFile,
                        depth = depth + 1
                    )
                }
            }
        } else {
            FileChangeItem(
                change = node.fileChange!!,
                changelists = changelists,
                onMoveFile = onMoveFile,
                onClick = { onFileClick(node.fullPath!!) },
                onDragStart = { onDragStart(node.fullPath!!) },
                onDragEnd = onDragEnd,
                isDragging = node.fullPath == draggingFile,
                modifier = Modifier.padding(start = (depth * 16).dp)
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChangelistTab(
    changelist: Changelist,
    isSelected: Boolean,
    isHovered: Boolean,
    fileCount: Int,
    onClick: () -> Unit,
    onPositioned: (androidx.compose.ui.geometry.Rect) -> Unit
) {
    val backgroundColor = when {
        isSelected -> Color.White
        isHovered -> Color(0xFFE3F2FD)
        else -> Color.Transparent
    }
    val textColor = if (isSelected) Color(0xFF0071E3) else Color.Gray
    
    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .onGloballyPositioned { layoutCoordinates ->
                val rect = androidx.compose.ui.geometry.Rect(
                    layoutCoordinates.positionInParent().x,
                    layoutCoordinates.positionInParent().y,
                    layoutCoordinates.positionInParent().x + layoutCoordinates.size.width,
                    layoutCoordinates.positionInParent().y + layoutCoordinates.size.height
                )
                onPositioned(rect)
            }
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
fun FileChangeItem(
    change: FileChange,
    changelists: List<Changelist>,
    onMoveFile: (String, String) -> Unit,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isDragging) 0.5f else 1f)
            .padding(start = 24.dp, top = 2.dp, bottom = 2.dp, end = 8.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { _, _ -> }
                )
            }
            .clickable { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileStatusIcon(change.status)
            Text(
                change.fileName,
                fontSize = 13.sp
            )
        }

        Box {
            IconButton(
                onClick = { showContextMenu = true },
                modifier = Modifier.size(24.dp)
            ) {
                Text("⋮", fontSize = 16.sp)
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
}

@Composable
fun DiffViewer(diff: String?) {
    if (diff == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select a file to view changes", color = Color.Gray)
        }
        return
    }

    val lines = diff.lines()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(scrollState)
            .padding(8.dp)
    ) {
        lines.forEach { line ->
            val backgroundColor = when {
                line.startsWith("+") -> Color(0xFFE8F5E9)
                line.startsWith("-") -> Color(0xFFFFEBEE)
                line.startsWith("@@") -> Color(0xFFF3E5F5)
                else -> Color.Transparent
            }
            val textColor = when {
                line.startsWith("+") -> Color(0xFF2E7D32)
                line.startsWith("-") -> Color(0xFFC62828)
                line.startsWith("@@") -> Color(0xFF7B1FA2)
                else -> Color.Black
            }

            Surface(
                color = backgroundColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    line,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
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

@Composable
fun CommitPanel(
    message: String,
    onMessageChange: (String) -> Unit,
    onCommit: () -> Unit,
    isEnabled: Boolean,
    isLoading: Boolean,
    compact: Boolean = false
) {
    if (compact) {
        // Compact horizontal layout
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            color = Color.White,
            elevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = message,
                    onValueChange = onMessageChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Commit message", fontSize = 13.sp) },
                    singleLine = true,
                    enabled = !isLoading,
                    colors = TextFieldDefaults.textFieldColors(
                        backgroundColor = Color(0xFFF2F2F7),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = MaterialTheme.shapes.medium
                )
                
                Button(
                    onClick = onCommit,
                    enabled = isEnabled && !isLoading && message.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF0071E3),
                        disabledBackgroundColor = Color(0xFFF2F2F7)
                    ),
                    elevation = ButtonDefaults.elevation(0.dp, 0.dp),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.height(40.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Commit", fontSize = 13.sp, color = if (isEnabled && !isLoading && message.isNotBlank()) Color.White else Color.Gray)
                }
            }
        }
    } else {
        // Original vertical layout
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            TextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Commit message") },
                maxLines = 4,
                enabled = !isLoading
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onCommit,
                modifier = Modifier.align(Alignment.End),
                enabled = isEnabled && !isLoading && message.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Commit")
            }
        }
    }
}
@Composable
fun CloneDialog(
    onDismiss: () -> Unit,
    onClone: (String, File) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone Repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Repository URL") },
                    placeholder = { Text("e.g. git@github.com:visio-soft/gitabc.git") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("Destination path") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    
                    IconButton(onClick = {
                        val chooser = JFileChooser()
                        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            path = chooser.selectedFile.absolutePath
                        }
                    }) {
                        Text("📂")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (url.isNotBlank() && path.isNotBlank()) onClone(url, File(path)) },
                enabled = url.isNotBlank() && path.isNotBlank(),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0071E3))
            ) {
                Text("Clone", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        modifier = Modifier.width(500.dp)
    )
}
