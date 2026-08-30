package com.zhuanz.autoleger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY time DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Delete
    suspend fun delete(tx: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity): Long

    @Delete
    suspend fun delete(category: CategoryEntity)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority DESC, id")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT MAX(priority) FROM rules")
    suspend fun maxPriority(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RuleEntity): Long

    @Delete
    suspend fun delete(rule: RuleEntity)
}

@Dao
interface PendingEntryDao {
    @Query("SELECT * FROM pending_entries ORDER BY time DESC")
    fun observeAll(): Flow<List<PendingEntryEntity>>

    @Query("SELECT * FROM pending_entries WHERE id = :id")
    suspend fun getById(id: Long): PendingEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PendingEntryEntity): Long

    @Query("DELETE FROM pending_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}
