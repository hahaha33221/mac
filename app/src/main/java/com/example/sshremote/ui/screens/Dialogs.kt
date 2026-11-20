package com.example.sshremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.sshremote.data.local.AuthType
import com.example.sshremote.data.local.HostEntity
import com.example.sshremote.data.local.SnippetEntity
import com.example.sshremote.data.repository.HostInput
import com.example.sshremote.ui.viewmodel.HostKeyPrompt

/** 호스트 추가/수정. 수정 시 비밀번호 칸을 비워 두면 기존 값을 유지한다. */
@Composable
fun HostEditDialog(
    initial: HostEntity?,
    onDismiss: () -> Unit,
    onSave: (HostInput) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "맥북") }
    var host by rememberSaveable { mutableStateOf(initial?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by rememberSaveable { mutableStateOf(initial?.username ?: "") }
    var authType by rememberSaveable { mutableStateOf(initial?.authType ?: AuthType.PASSWORD) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    val portNum = port.toIntOrNull()
    val needSecret = initial == null || initial.authType != authType
    val valid = label.isNotBlank() && host.isNotBlank() && user.isNotBlank() &&
        portNum != null && portNum in 1..65535 && (!needSecret || secret.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "호스트 추가" else "호스트 수정") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(label, { label = it }, label = { Text("이름") }, singleLine = true)
                OutlinedTextField(
                    host, { host = it }, label = { Text("IP 또는 호스트 이름") },
                    placeholder = { Text("192.168.0.10 / macbook.local") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("포트") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(user, { user = it }, label = { Text("계정 이름") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(authType == AuthType.PASSWORD, { authType = AuthType.PASSWORD }, label = { Text("비밀번호") })
                    FilterChip(authType == AuthType.PRIVATE_KEY, { authType = AuthType.PRIVATE_KEY }, label = { Text("개인키") })
                }
                val keepHint: (@Composable () -> Unit)? =
                    if (needSecret) null else { { Text("비워 두면 기존 값 유지") } }
                if (authType == AuthType.PASSWORD) {
                    OutlinedTextField(
                        secret, { secret = it }, label = { Text("비밀번호") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = keepHint,
                    )
                } else {
                    OutlinedTextField(
                        secret, { secret = it }, label = { Text("개인키 (OpenSSH / PEM 붙여넣기)") },
                        minLines = 3, maxLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        supportingText = keepHint,
                    )
                    OutlinedTextField(
                        passphrase, { passphrase = it }, label = { Text("키 암호 (없으면 비움)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                Text(
                    "비밀번호 · 키는 Android Keystore 로 암호화해 이 기기에만 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        HostInput(
                            id = initial?.id ?: 0, label = label, host = host, port = portNum ?: 22,
                            username = user, authType = authType, secret = secret, passphrase = passphrase,
                        ),
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun SnippetEditDialog(
    hostId: Long,
    initial: SnippetEntity?,
    passwordAuth: Boolean,
    onDismiss: () -> Unit,
    onSave: (SnippetEntity) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial?.title ?: "") }
    var command by rememberSaveable { mutableStateOf(initial?.command ?: "") }
    var dangerous by rememberSaveable { mutableStateOf(initial?.dangerous ?: false) }
    var sudo by rememberSaveable { mutableStateOf(initial?.useSudoPassword ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "명령 추가" else "명령 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("버튼 이름") }, singleLine = true)
                OutlinedTextField(
                    command, { command = it }, label = { Text("셸 명령") },
                    placeholder = { Text("pmset sleepnow") }, minLines = 2,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                CheckRow("위험한 명령 (실행 전 한 번 더 확인)", dangerous) { dangerous = it }
                CheckRow("sudo 비밀번호 자동 입력", sudo, enabled = passwordAuth) { sudo = it }
                Text(
                    if (passwordAuth) "명령이 \"sudo \" 로 시작하면 호스트 비밀번호를 sudo 에 넘깁니다."
                    else "개인키 호스트는 sudo 비밀번호를 넘길 수 없습니다. 맥에서 sudoers NOPASSWD 를 설정하세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && command.isNotBlank(),
                onClick = {
                    val base = initial ?: SnippetEntity(hostId = hostId, title = "", command = "")
                    onSave(
                        base.copy(
                            title = title.trim(), command = command.trim(), dangerous = dangerous,
                            useSudoPassword = sudo && passwordAuth,
                        ),
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 위험 명령 실행 전 확인 */
@Composable
fun ConfirmRunDialog(snippet: SnippetEntity, hostLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("'${snippet.title}' 실행할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$hostLabel 에서 아래 명령을 실행합니다. 실행하면 되돌릴 수 없습니다.")
                Text(snippet.command, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("실행", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 서버 지문 확인: 처음 접속(승인) 또는 지문 바뀜(경고) */
@Composable
fun HostKeyDialog(prompt: HostKeyPrompt, onApprove: () -> Unit, onReject: () -> Unit) {
    val mismatch = prompt.expected != null
    val warningIcon: (@Composable () -> Unit)? = if (mismatch) {
        { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
    } else null
    AlertDialog(
        onDismissRequest = onReject,
        icon = warningIcon,
        title = { Text(if (mismatch) "서버 지문이 바뀌었습니다" else "처음 접속하는 서버") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mismatch) {
                    Text(
                        "'${prompt.hostLabel}' 의 지문이 저장된 값과 다릅니다. 맥을 다시 설치했거나 IP 가 다른 기기로 바뀐 경우가 아니라면 " +
                            "누군가 연결을 가로채고 있을 수 있습니다.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("저장된 지문", style = MaterialTheme.typography.labelMedium)
                    Text(prompt.expected.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("'${prompt.hostLabel}' 에 처음 접속합니다. 맥의 터미널에서 아래 명령으로 지문이 같은지 확인한 뒤 승인하세요.")
                    Text(
                        "ssh-keygen -lf /etc/ssh/ssh_host_${keyFileName(prompt.key.algorithm)}_key.pub",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("서버 지문 (${prompt.key.algorithm})", style = MaterialTheme.typography.labelMedium)
                Text(prompt.key.sha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(prompt.key.md5, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text(
                    if (mismatch) "새 지문으로 바꾸고 접속" else "승인하고 접속",
                    color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onReject) { Text("취소") } },
    )
}

private fun keyFileName(algorithm: String): String = when {
    algorithm.contains("ed25519") -> "ed25519"
    algorithm.contains("ecdsa") -> "ecdsa"
    algorithm.contains("rsa") -> "rsa"
    else -> "*"
}
