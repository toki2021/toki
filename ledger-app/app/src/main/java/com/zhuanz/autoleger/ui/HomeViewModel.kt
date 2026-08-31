package com.zhuanz.autoleger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhuanz.autoleger.data.AppContainer
import com.zhuanz.autoleger.data.TransactionEntity
import com.zhuanz.autoleger.data.TransactionRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel —— 删除与 Undo 逻辑的中心。
 *
 * 替代了之前的全局 `deleteScope`（一个永不停释放的
 * 顶级 CoroutineScope，导致 Activity 销毁后协程仍挂起）。
 * 所有协程均在 viewModelScope 上运行，生命周期与 ViewModel 一致。
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val repo: TransactionRepository = container.transactionRepository

    val transactions: Flow<List<TransactionEntity>> = repo.observeAll()
    val categories = container.categoryDao.observeAll()

    /** 待确认的删除目标；用户在 Dialog 中点"删除"后真正执行 */
    var pendingDeleteTx: TransactionEntity? = null
        private set

    /** 一次性事件：通知 UI 显示 Undo Snackbar（payload 为被删实体） */
    private val _undoEvents = Channel<TransactionEntity>(Channel.BUFFERED)
    val undoEvents: Flow<TransactionEntity> = _undoEvents.receiveAsFlow()

    /** 记录待删除目标（✕按钮点击时） */
    fun requestDelete(tx: TransactionEntity) {
        pendingDeleteTx = tx
    }

    /** Dialog 取消或重组后清空待删除目标 */
    fun clearPendingDelete() {
        pendingDeleteTx = null
    }

    /**
     * 真正执行删除：先读取完整实体，再按 id 删除，
     * 最后向 UI 发送 Undo Snackbar 事件。
     */
    fun confirmDelete() {
        val target = pendingDeleteTx ?: return
        pendingDeleteTx = null
        viewModelScope.launch {
            val entity = repo.getById(target.id) ?: return@launch
            repo.deleteById(entity.id)
            _undoEvents.send(entity)
        }
    }

    /** Snackbar "撤销"回调：把被删实体重新插回（id 主键不变） */
    fun undo(tx: TransactionEntity) {
        viewModelScope.launch { repo.restore(tx) }
    }
}
