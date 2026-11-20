package com.example.sshremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sshremote.data.local.AuthType
import com.example.sshremote.data.local.SnippetEntity
import com.example.sshremote.ui.viewmodel.LogEntry
import com.example.sshremote.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetScreen(vm: MainViewModel, hostId: Long, onBack: () -> Unit) {
    val hostFlow = remember(hostId) { vm.host(hostId) }
    val snippetsFlow = remember(hostId) { vm.snippets(hostId) }
    val host by hostFlow.collectAsState(initial = null)
    val snippets by snippetsFlow.collectAsState(initial = emptyList())
    val running by vm.running.collectAsStateWithLifecycle()
    val allLog by vm.log.collectAsStateWithLifecycle()
    val log = allLog.filter { it.hostId == hostId }
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var editHost by remember { mutableStateOf(false) }
    var editSnippet by remember { mutableStateOf<SnippetEntity?>(null) }
    var addSnippet by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<SnippetEntity?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val current = host
    val testing = MainViewModel.testKey(hostId) in running

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(current?.label ?: "")
                        current?.let {
                            Text(
                                "${it.username}@${it.host}:${it.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    IconButton(onClick = { vm.testConnection(hostId) }, enabled = !testing) {
                        if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.NetworkCheck, contentDescription = "연결 테스트")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "메뉴") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("호스트 수정") }, onClick = { menu = false; editHost = true })
                            DropdownMenuItem(
                                text = { Text("맥북 기본 명령 추가") },
                                onClick = { menu = false; vm.addMacPresets(hostId) },
                            )
                            DropdownMenuItem(
                                text = { Text("서버 지문 다시 확인") },
                                onClick = { menu = false; vm.forgetFingerprint(hostId) },
                            )
                            DropdownMenuItem(text = { Text("실행 기록 지우기") }, onClick = { menu = false; vm.clearLog() })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { addSnippet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "명령 추가")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (snippets.isEmpty()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("등록된 명령이 없습니다", style = MaterialTheme.typography.titleSmall)
                            Text("잠자기 · 화면 끄기 · 재시작 · 끄기 · 상태 확인 명령을 한 번에 추가할 수 있습니다.")
                            Button(onClick = { vm.addMacPresets(hostId) }) { Text("맥북 기본 명령 추가") }
                        }
                    }
                }
            }
            items(snippets, key = { it.id }) { snippet ->
                SnippetCard(
                    snippet = snippet,
                    running = snippet.id in running,
                    onRun = { if (snippet.dangerous) confirm = snippet else vm.runSnippet(snippet) },
                    onEdit = { editSnippet = snippet },
                    onDelete = { vm.deleteSnippet(snippet) },
                )
            }
            item {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("실행 결과", style = MaterialTheme.typography.titleMedium)
                    if (log.isEmpty()) {
                        Text(
                            "아직 실행한 명령이 없습니다. 오른쪽 위 버튼으로 연결을 먼저 시험해 보세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(log, key = { "log-${it.id}" }) { entry -> LogCard(entry) }
        }
    }

    if (editHost && current != null) {
        HostEditDialog(current, onDismiss = { editHost = false }) { input ->
            vm.saveHost(input) { editHost = false }
        }
    }
    if (addSnippet) {
        SnippetEditDialog(
            hostId, null, passwordAuth = current?.authType == AuthType.PASSWORD,
            onDismiss = { addSnippet = false },
        ) { vm.saveSnippet(it); addSnippet = false }
    }
    editSnippet?.let { s ->
        SnippetEditDialog(
            hostId, s, passwordAuth = current?.authType == AuthType.PASSWORD,
            onDismiss = { editSnippet = null },
        ) { vm.saveSnippet(it); editSnippet = null }
    }
    confirm?.let { s ->
        ConfirmRunDialog(s, current?.label ?: "", onDismiss = { confirm = null }) {
            confirm = null
            vm.runSnippet(s)
        }
    }
    hostKeyPrompt?.let { prompt ->
        HostKeyDialog(prompt, onApprove = vm::approveHostKey, onReject = vm::rejectHostKey)
    }
}

@Composable
private fun SnippetCard(
    snippet: SnippetEntity,
    running: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (snippet.dangerous) {
                        Icon(
                            Icons.Filled.Warning, contentDescription = "위험",
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(snippet.title, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    snippet.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Button(
                onClick = onRun,
                enabled = !running,
                colors = if (snippet.dangerous) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) else ButtonDefaults.buttonColors(),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("실행")
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "메뉴") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("수정") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("지우기") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun LogCard(entry: LogEntry) {
    val time = remember(entry.at) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.at)) }
    val result = entry.result
    val (status, isError) = when {
        entry.error != null -> "실패" to true
        result == null -> "?" to true
        result.timedOut -> "시간 초과" to true
        result.disconnected -> "연결 끊김" to false
        result.exitStatus == 0 -> "성공 (종료 코드 0)" to false
        else -> "종료 코드 ${result.exitStatus}" to true
    }
    var expanded by remember { mutableStateOf(true) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${entry.title} · $time", style = MaterialTheme.typography.titleSmall)
                    Text(
                        status + (result?.let { " · ${it.durationMs} ms" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "펼치기") }
            }
            if (expanded) {
                entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (result != null) {
                    if (result.disconnected) {
                        Text(
                            "명령 실행 중 연결이 끊겼습니다. 끄기 · 재시작 명령이라면 정상입니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (result.stdout.isNotBlank()) OutputBlock("stdout", result.stdout)
                    if (result.stderr.isNotBlank()) OutputBlock("stderr", result.stderr)
                    if (result.stdout.isBlank() && result.stderr.isBlank() && !result.disconnected) {
                        Text("(출력 없음)", style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Text(
                    "$ ${entry.command}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OutputBlock(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text.trimEnd(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            )
        }
    }
}
