package com.example.sshremote.data.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

/** 접속 정보. 비밀번호/키는 실행 직전에 복호화해서 넘기고, 끝나면 버린다. */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: SshAuth,
    /** 이전에 승인한 서버 지문 (SHA256:...). null 이면 처음 접속. */
    val trustedFingerprint: String?,
)

sealed interface SshAuth {
    data class Password(val password: String) : SshAuth
    data class PrivateKey(val privateKeyPem: String, val passphrase: String?) : SshAuth
}

data class HostKeyInfo(val sha256: String, val md5: String, val algorithm: String)

data class CommandResult(
    val stdout: String,
    val stderr: String,
    /** 명령이 끝나기 전에 연결이 끊기면 null (예: 종료/재시작 명령) */
    val exitStatus: Int?,
    val durationMs: Long,
    /** 실행 도중 연결이 끊김. shutdown / reboot 에서는 정상적인 결과. */
    val disconnected: Boolean = false,
    val timedOut: Boolean = false,
)

sealed interface RunOutcome {
    data class Success(val result: CommandResult) : RunOutcome
    /** 처음 보는 서버. 사용자가 지문을 확인하고 승인하면 저장 후 다시 실행. */
    data class UnknownHostKey(val key: HostKeyInfo) : RunOutcome
    /** 저장된 지문과 다름. 중간자 공격 또는 서버 재설치 가능성. */
    data class HostKeyMismatch(val expected: String, val actual: HostKeyInfo) : RunOutcome
    data class AuthFailed(val message: String) : RunOutcome
    data class Failed(val message: String) : RunOutcome
}

/**
 * SSHJ 기반 단일 명령 실행기.
 * - 모든 네트워크 작업은 Dispatchers.IO
 * - 연결 타임아웃 + 명령 타임아웃 (타임아웃이면 연결을 끊어 막힌 읽기를 풀어줌)
 * - 서버 지문(SHA-256)을 저장된 값과 비교. 처음이면 UnknownHostKey 로 돌려 사용자 승인을 받음
 */
class SshCommandRunner(
    private val connectTimeoutMs: Int = 10_000,
    private val maxOutputBytes: Int = 256 * 1024,
) {

    suspend fun run(
        target: SshTarget,
        command: String,
        /** sudo 비밀번호. 주면 맨 앞 "sudo " 를 "sudo -S -p '' " 로 바꾸고 표준 입력으로 넘김 */
        sudoPassword: String? = null,
        commandTimeoutMs: Long = 30_000,
    ): RunOutcome = withContext(Dispatchers.IO) {
        val verifier = FingerprintVerifier(target.trustedFingerprint)
        val ssh = SSHClient(DefaultConfig())
        val started = System.currentTimeMillis()
        try {
            ssh.connectTimeout = connectTimeoutMs
            ssh.timeout = connectTimeoutMs
            ssh.addHostKeyVerifier(verifier)

            try {
                ssh.connect(target.host, target.port)
            } catch (e: TransportException) {
                verifier.rejected?.let { seen ->
                    return@withContext if (target.trustedFingerprint == null) {
                        RunOutcome.UnknownHostKey(seen)
                    } else {
                        RunOutcome.HostKeyMismatch(target.trustedFingerprint, seen)
                    }
                }
                return@withContext RunOutcome.Failed("연결 실패: ${e.message ?: e.javaClass.simpleName}")
            }

            try {
                when (val auth = target.auth) {
                    is SshAuth.Password -> ssh.authPassword(target.username, auth.password)
                    is SshAuth.PrivateKey -> {
                        val finder = auth.passphrase?.let { StaticPasswordFinder(it) }
                        val keys = ssh.loadKeys(auth.privateKeyPem, null, finder)
                        ssh.authPublickey(target.username, keys)
                    }
                }
            } catch (e: UserAuthException) {
                return@withContext RunOutcome.AuthFailed("인증 실패: 계정 이름이나 비밀번호/키를 확인하세요 (${e.message})")
            }

            val finalCommand = if (sudoPassword != null) wrapSudo(command) else command
            RunOutcome.Success(exec(ssh, finalCommand, sudoPassword, commandTimeoutMs, started))
        } catch (e: UnknownHostException) {
            RunOutcome.Failed("호스트를 찾을 수 없습니다: ${target.host}")
        } catch (e: ConnectException) {
            RunOutcome.Failed("연결 거부: ${target.host}:${target.port} 에서 SSH(원격 로그인)가 켜져 있는지 확인하세요")
        } catch (e: NoRouteToHostException) {
            RunOutcome.Failed("경로 없음: 같은 Wi-Fi(네트워크)에 있는지 확인하세요")
        } catch (e: SocketTimeoutException) {
            RunOutcome.Failed("연결 시간 초과 (${connectTimeoutMs / 1000}초): IP 와 네트워크를 확인하세요")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RunOutcome.Failed("오류: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { ssh.disconnect() }
        }
    }

    private suspend fun exec(
        ssh: SSHClient,
        command: String,
        sudoPassword: String?,
        commandTimeoutMs: Long,
        started: Long,
    ): CommandResult = coroutineScope {
        val out = LimitedBuffer(maxOutputBytes)
        val err = LimitedBuffer(maxOutputBytes)
        var disconnected = false
        var exitStatus: Int? = null
        var timedOut = false

        val session = ssh.startSession()
        try {
            val cmd = session.exec(command)
            if (sudoPassword != null) {
                runCatching {
                    cmd.outputStream.write((sudoPassword + "\n").toByteArray())
                    cmd.outputStream.flush()
                }
            }
            runCatching { cmd.outputStream.close() } // 표준 입력 EOF: 입력을 기다리며 멈추지 않게

            val outJob = async(Dispatchers.IO) { pump(cmd.inputStream, out) }
            val errJob = async(Dispatchers.IO) { pump(cmd.errorStream, err) }

            val finished = withTimeoutOrNull(commandTimeoutMs) {
                val a = outJob.await()
                val b = errJob.await()
                a && b
            }
            when (finished) {
                null -> {
                    timedOut = true
                    // 막혀 있는 읽기를 풀기 위해 연결 자체를 끊는다
                    // (cmd.close() 는 원격 명령이 끝날 때까지 기다리므로 쓰지 않는다)
                    runCatching { ssh.disconnect() }
                }
                false -> disconnected = true
                true -> {
                    runCatching { cmd.join(3, TimeUnit.SECONDS) }
                    exitStatus = cmd.exitStatus
                    if (exitStatus == null && !ssh.isConnected) disconnected = true
                }
            }
            outJob.await(); errJob.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // shutdown / reboot 처럼 실행 중 연결이 끊기는 경우
            disconnected = true
        } finally {
            runCatching { session.close() }
        }

        CommandResult(
            stdout = out.text(),
            stderr = err.text(),
            exitStatus = exitStatus,
            durationMs = System.currentTimeMillis() - started,
            disconnected = disconnected && !timedOut,
            timedOut = timedOut,
        )
    }

    /** 끝까지 읽으면 true, 도중에 연결이 끊기면 false */
    private fun pump(input: InputStream, sink: LimitedBuffer): Boolean = try {
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            sink.write(buf, n)
        }
        true
    } catch (e: Exception) {
        false
    }

    private class LimitedBuffer(private val limit: Int) {
        private val bytes = ByteArrayOutputStream()
        private var truncated = false

        @Synchronized
        fun write(buf: ByteArray, n: Int) {
            val room = limit - bytes.size()
            if (room <= 0) { truncated = true; return }
            bytes.write(buf, 0, minOf(n, room))
            if (n > room) truncated = true
        }

        @Synchronized
        fun text(): String = bytes.toString(Charsets.UTF_8.name()) +
            if (truncated) "\n… (출력이 너무 길어 일부만 표시)" else ""
    }

    private class StaticPasswordFinder(private val secret: String) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = secret.toCharArray()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    /** 저장된 SHA-256 지문과 같을 때만 통과. 다르면 본 키를 기록해 두고 거부. */
    private class FingerprintVerifier(private val trusted: String?) : HostKeyVerifier {
        @Volatile var rejected: HostKeyInfo? = null

        override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
            val info = describe(key)
            if (trusted != null && trusted == info.sha256) return true
            rejected = info
            return false
        }

        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
    }

    companion object {
        fun describe(key: PublicKey): HostKeyInfo {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val sha256 = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
            val md5 = "MD5:" + SecurityUtils.getFingerprint(key)
            val algorithm = net.schmizz.sshj.common.KeyType.fromKey(key).toString()
            return HostKeyInfo(sha256, md5, algorithm)
        }

        /** 맨 앞의 "sudo " 를 표준 입력으로 비밀번호를 받는 형태로 바꾼다 */
        fun wrapSudo(command: String): String {
            val trimmed = command.trimStart()
            return if (trimmed.startsWith("sudo ")) {
                "sudo -S -p '' " + trimmed.removePrefix("sudo ")
            } else {
                command
            }
        }
    }
}
