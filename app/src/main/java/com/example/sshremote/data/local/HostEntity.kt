package com.example.sshremote.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class AuthType { PASSWORD, PRIVATE_KEY }

/**
 * 접속 대상 (예: 맥북).
 * encryptedSecret: 비밀번호 또는 개인키(PEM)를 CryptoManager 로 암호화한 값. 평문은 저장하지 않는다.
 */
@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val encryptedSecret: String,
    /** 개인키 암호(있을 때만), 암호화됨 */
    val encryptedPassphrase: String? = null,
    /** 사용자가 승인한 서버 지문 "SHA256:..." */
    val hostKeyFingerprint: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observe(id: Long): Flow<HostEntity?>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: Long): HostEntity?

    @Insert
    suspend fun insert(host: HostEntity): Long

    @Update
    suspend fun update(host: HostEntity)

    @Delete
    suspend fun delete(host: HostEntity)

    @Query("UPDATE hosts SET hostKeyFingerprint = :fingerprint WHERE id = :id")
    suspend fun setFingerprint(id: Long, fingerprint: String?)
}
