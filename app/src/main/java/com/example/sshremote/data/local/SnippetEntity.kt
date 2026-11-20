package com.example.sshremote.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 원클릭 명령.
 * dangerous: 실행 전 확인 창 (종료 · 재시작 등)
 * useSudoPassword: 맨 앞 "sudo " 에 호스트 비밀번호를 표준 입력으로 넘김 (비밀번호 인증 호스트만)
 */
@Entity(
    tableName = "snippets",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("hostId")],
)
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val title: String,
    val command: String,
    val dangerous: Boolean = false,
    val useSudoPassword: Boolean = false,
    val sortOrder: Int = 0,
)

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets WHERE hostId = :hostId ORDER BY sortOrder, id")
    fun observeForHost(hostId: Long): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun get(id: Long): SnippetEntity?

    @Insert
    suspend fun insert(snippet: SnippetEntity): Long

    @Insert
    suspend fun insertAll(snippets: List<SnippetEntity>)

    @Update
    suspend fun update(snippet: SnippetEntity)

    @Delete
    suspend fun delete(snippet: SnippetEntity)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM snippets WHERE hostId = :hostId")
    suspend fun nextSortOrder(hostId: Long): Int
}
