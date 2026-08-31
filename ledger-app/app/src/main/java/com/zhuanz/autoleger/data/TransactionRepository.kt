package com.zhuanz.autoleger.data

import kotlinx.coroutines.flow.Flow

/**
 * 交易数据仓库：封装 TransactionDao 的读写操作，
 * 让 UI / ViewModel 不直接依赖 DAO。
 *
 * Undo 恢复使用带主键的 insert（dao 的 OnConflictStrategy.REPLACE），
 * 因此删除前的 id / refundOf 引用都能完整保留。
 */
class TransactionRepository(private val dao: TransactionDao) {

    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    suspend fun getById(id: Long): TransactionEntity? = dao.getById(id)

    suspend fun insert(tx: TransactionEntity): Long = dao.insert(tx)

    suspend fun update(tx: TransactionEntity) = dao.update(tx)

    /** 按 id 删除一条交易 */
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /**
     * 恢复一条先前被删除的交易（用于 Snackbar Undo）。
     * 由于 dao.insert 是 REPLACE 冲突策略且实体带主键，
     * 恢复后 id 不变，refundOf 等引用保持有效。
     */
    suspend fun restore(tx: TransactionEntity) {
        dao.insert(tx)
    }
}
