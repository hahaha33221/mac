package com.example.sshremote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sshremote.data.local.HostEntity
import com.example.sshremote.data.local.SnippetEntity
import com.example.sshremote.data.repository.HostInput
import com.example.sshremote.data.repository.RemoteControlRepository
import com.example.sshremote.data.ssh.CommandResult
import com.example.sshremote.data.ssh.HostKeyInfo
import com.example.sshremote.data.ssh.RunOutcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 실행 기록 한 줄 (앱을 끄면 사라짐) */
data class LogEntry(
    val id: Long,
    val hostId: Long,
    val hostLabel: String,
    val title: String,
    val command: String,
    val at: Long,
    val result: CommandResult? = null,
    val error: String? = null,
)

/** 서버 지문 확인 창에 띄울 내용. 승인하면 저장 후 같은 명령을 다시 실행한다. */
data class HostKeyPrompt(
    val hostId: Long,
    val hostLabel: String,
    val key: HostKeyInfo,
    /** null 이면 처음 접속, 값이 있으면 저장된 지문과 다름(경고) */
    val expected: String?,
    internal val retry: RunRequest,
)

data class RunRequest(
    val hostId: Long,
    val title: String,
    val command: String,
    val useSudoPassword: Boolean,
    /** 실행 중 표시용 (스니펫 id, 연결 테스트는 -호스트 id) */
    val runningKey: Long,
)

class MainViewModel(private val repo: RemoteControlRepository) : ViewModel() {

    val hosts: StateFlow<List<HostEntity>> =
        repo.observeHosts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun host(id: Long): Flow<HostEntity?> = repo.observeHost(id)
    fun snippets(hostId: Long): Flow<List<SnippetEntity>> = repo.observeSnippets(hostId)

    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val running: StateFlow<Set<Long>> = _running.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var nextLogId = 1L

    // ---- 호스트 ----

    fun saveHost(input: HostInput, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        runCatching { repo.saveHost(input) }
            .onSuccess { onSaved(it); _messages.send("저장했습니다") }
            .onFailure { _messages.send(it.message ?: "저장 실패") }
    }

    fun deleteHost(host: HostEntity) = viewModelScope.launch {
        repo.deleteHost(host)
        _messages.send("'${host.label}' 를 지웠습니다")
    }

    fun forgetFingerprint(hostId: Long) = viewModelScope.launch {
        repo.forgetFingerprint(hostId)
        _messages.send("저장된 서버 지문을 지웠습니다. 다음 접속 때 다시 확인합니다")
    }

    // ---- 스니펫 ----

    fun saveSnippet(snippet: SnippetEntity) = viewModelScope.launch { repo.saveSnippet(snippet) }
    fun deleteSnippet(snippet: SnippetEntity) = viewModelScope.launch { repo.deleteSnippet(snippet) }
    fun addMacPresets(hostId: Long) = viewModelScope.launch {
        repo.addMacPresets(hostId)
        _messages.send("맥북 기본 명령 5개를 추가했습니다")
    }

    // ---- 실행 ----

    fun runSnippet(snippet: SnippetEntity) = execute(
        RunRequest(snippet.hostId, snippet.title, snippet.command, snippet.useSudoPassword, snippet.id),
    )

    fun testConnection(hostId: Long) = execute(
        RunRequest(hostId, "연결 테스트", "uname -a && uptime", useSudoPassword = false, runningKey = testKey(hostId)),
    )

    private fun execute(req: RunRequest) {
        if (req.runningKey in _running.value) return
        viewModelScope.launch {
            _running.update { it + req.runningKey }
            try {
                val label = repo.getHost(req.hostId)?.label ?: "?"
                when (val outcome = repo.run(req.hostId, req.command, req.useSudoPassword)) {
                    is RunOutcome.Success -> addLog(label, req, result = outcome.result)
                    is RunOutcome.UnknownHostKey ->
                        _hostKeyPrompt.value = HostKeyPrompt(req.hostId, label, outcome.key, null, req)
                    is RunOutcome.HostKeyMismatch ->
                        _hostKeyPrompt.value = HostKeyPrompt(req.hostId, label, outcome.actual, outcome.expected, req)
                    is RunOutcome.AuthFailed -> addLog(label, req, error = outcome.message)
                    is RunOutcome.Failed -> addLog(label, req, error = outcome.message)
                }
            } finally {
                _running.update { it - req.runningKey }
            }
        }
    }

    fun approveHostKey() {
        val prompt = _hostKeyPrompt.value ?: return
        _hostKeyPrompt.value = null
        viewModelScope.launch {
            repo.trustFingerprint(prompt.hostId, prompt.key.sha256)
            execute(prompt.retry)
        }
    }

    fun rejectHostKey() {
        val prompt = _hostKeyPrompt.value ?: return
        _hostKeyPrompt.value = null
        addLog(
            prompt.hostLabel, prompt.retry,
            error = if (prompt.expected == null) "서버 지문을 승인하지 않아 접속하지 않았습니다"
            else "서버 지문이 달라 접속을 막았습니다",
        )
    }

    fun clearLog() { _log.value = emptyList() }

    private fun addLog(label: String, req: RunRequest, result: CommandResult? = null, error: String? = null) {
        val entry = LogEntry(nextLogId++, req.hostId, label, req.title, req.command, System.currentTimeMillis(), result, error)
        _log.update { (listOf(entry) + it).take(MAX_LOG) }
    }

    companion object {
        /** 연결 테스트의 실행 중 표시 키 (스니펫 id 와 겹치지 않게 음수) */
        fun testKey(hostId: Long): Long = -hostId
        private const val MAX_LOG = 50
    }
}
