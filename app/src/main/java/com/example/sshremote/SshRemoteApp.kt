package com.example.sshremote

import android.app.Application
import com.example.sshremote.data.local.AppDatabase
import com.example.sshremote.data.local.CryptoManager
import com.example.sshremote.data.repository.RemoteControlRepository
import com.example.sshremote.data.ssh.SshCommandRunner
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/** 간단한 수동 의존성 주입: 앱 전체에서 저장소 하나를 공유 */
class SshRemoteApp : Application() {

    lateinit var repository: RemoteControlRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // 안드로이드 기본 "BC" 는 기능이 잘려 있어 SSHJ(ed25519, curve25519 등)가 실패한다 → 전체 BouncyCastle 로 교체
        // 맨 뒤에 추가해서 AndroidKeyStore 같은 기본 제공자 선택에는 영향을 주지 않는다
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())

        repository = RemoteControlRepository(
            db = AppDatabase.create(this),
            crypto = CryptoManager(),
            runner = SshCommandRunner(),
        )
    }
}
