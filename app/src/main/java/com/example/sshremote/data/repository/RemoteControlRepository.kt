package com.example.sshremote.data.repository

import com.example.sshremote.data.local.AppDatabase
import com.example.sshremote.data.local.AuthType
import com.example.sshremote.data.local.CryptoManager
import com.example.sshremote.data.local.HostEntity
import com.example.sshremote.data.local.SnippetEntity
import com.example.sshremote.data.ssh.RunOutcome
import com.example.sshremote.data.ssh.SshAuth
import com.example.sshremote.data.ssh.SshCommandRunner
import com.example.sshremote.data.ssh.SshTarget
import kotlinx.coroutines.flow.Flow

/** 화면에서 입력받은 호스트 정보. secret 이 비어 있으면(수정 시) 기존 값을 유지한다. */
data class HostInput(
    val id: Long = 0,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val secret: String,
    val passphrase: String,
)

class RemoteControlRepository(
    db: AppDatabase,
    private val crypto: CryptoManager,
    private val runner: SshCommandRunner,
) {
    private val hostDao = db.hostDao()
    private val snippetDao = db.snippetDao()

    fun observeHosts(): Flow<List<HostEntity>> = hostDao.observeAll()
    fun observeHost(id: Long): Flow<HostEntity?> = hostDao.observe(id)
    fun observeSnippets(hostId: Long): Flow<List<SnippetEntity>> = snippetDao.observeForHost(hostId)

    suspend fun saveHost(input: HostInput): Long {
        val existing = if (input.id != 0L) hostDao.get(input.id) else null
        val encryptedSecret = when {
            input.secret.isNotEmpty() -> crypto.encrypt(input.secret)
            existing != null && existing.authType == input.authType -> existing.encryptedSecret
            else -> throw IllegalArgumentException("비밀번호 또는 개인키를 입력하세요")
        }
        val encryptedPassphrase = when {
            input.authType != AuthType.PRIVATE_KEY -> null
            input.passphrase.isNotEmpty() -> crypto.encrypt(input.passphrase)
            else -> existing?.encryptedPassphrase.takeIf { input.secret.isEmpty() }
        }
        val entity = HostEntity(
            id = existing?.id ?: 0,
            label = input.label.trim(),
            host = input.host.trim(),
            port = input.port,
            username = input.username.trim(),
            authType = input.authType,
            encryptedSecret = encryptedSecret,
            encryptedPassphrase = encryptedPassphrase,
            // 주소가 바뀌면 다른 서버일 수 있으므로 지문 승인을 다시 받는다
            hostKeyFingerprint = existing
                ?.takeIf { it.host == input.host.trim() && it.port == input.port }
                ?.hostKeyFingerprint,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        return if (existing == null) hostDao.insert(entity) else { hostDao.update(entity); entity.id }
    }

    suspend fun getHost(id: Long): HostEntity? = hostDao.get(id)
    suspend fun deleteHost(host: HostEntity) = hostDao.delete(host)
    suspend fun trustFingerprint(hostId: Long, fingerprint: String) = hostDao.setFingerprint(hostId, fingerprint)
    suspend fun forgetFingerprint(hostId: Long) = hostDao.setFingerprint(hostId, null)

    suspend fun saveSnippet(snippet: SnippetEntity) {
        if (snippet.id == 0L) {
            snippetDao.insert(snippet.copy(sortOrder = snippetDao.nextSortOrder(snippet.hostId)))
        } else {
            snippetDao.update(snippet)
        }
    }

    suspend fun deleteSnippet(snippet: SnippetEntity) = snippetDao.delete(snippet)

    /** 맥북용 기본 스니펫 */
    suspend fun addMacPresets(hostId: Long) {
        val start = snippetDao.nextSortOrder(hostId)
        val presets = listOf(
            SnippetEntity(hostId = hostId, title = "상태 확인", command = "uptime && pmset -g batt"),
            SnippetEntity(hostId = hostId, title = "화면 끄기", command = "pmset displaysleepnow"),
            SnippetEntity(hostId = hostId, title = "맥북 잠자기", command = "pmset sleepnow"),
            SnippetEntity(
                hostId = hostId, title = "맥북 재시작", command = "sudo shutdown -r now",
                dangerous = true, useSudoPassword = true,
            ),
            SnippetEntity(
                hostId = hostId, title = "맥북 끄기", command = "sudo shutdown -h now",
                dangerous = true, useSudoPassword = true,
            ),
        ).mapIndexed { i, s -> s.copy(sortOrder = start + i) }
        snippetDao.insertAll(presets)
    }

    /** 명령 실행. 비밀번호/키는 여기서만 복호화하고 실행이 끝나면 참조를 버린다. */
    suspend fun run(hostId: Long, command: String, useSudoPassword: Boolean): RunOutcome {
        val host = hostDao.get(hostId) ?: return RunOutcome.Failed("호스트가 없습니다")
        // 복호화한 값은 이 함수 지역 변수로만 존재하고, 끝나면 참조가 사라진다 (디스크 · 로그에 남기지 않음)
        val secret: String
        val passphrase: String?
        try {
            secret = crypto.decrypt(host.encryptedSecret)
            passphrase = host.encryptedPassphrase?.let { crypto.decrypt(it) }
        } catch (e: Exception) {
            return RunOutcome.Failed("저장된 비밀 정보를 읽지 못했습니다. 호스트를 수정해 다시 입력하세요 (${e.message})")
        }
        val auth = when (host.authType) {
            AuthType.PASSWORD -> SshAuth.Password(secret)
            AuthType.PRIVATE_KEY -> SshAuth.PrivateKey(secret, passphrase)
        }
        val sudo = if (useSudoPassword && host.authType == AuthType.PASSWORD) secret else null
        return runner.run(
            target = SshTarget(host.host, host.port, host.username, auth, host.hostKeyFingerprint),
            command = command,
            sudoPassword = sudo,
        )
    }
}
