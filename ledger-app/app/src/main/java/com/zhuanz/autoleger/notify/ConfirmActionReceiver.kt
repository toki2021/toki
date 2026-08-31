package com.zhuanz.autoleger.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.data.EntryConfirmer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 处理确认通知上的【入账】/【忽略】按钮 */
class ConfirmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingId = intent.getLongExtra(ConfirmNotifier.EXTRA_PENDING_ID, -1)
        if (pendingId < 0) return
        val action = intent.action ?: return
        val container = (context.applicationContext as LedgerAppProvider).container

        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // goAsync 最多持有约 10s，留 8s 上限兜底，超时直接结束
                withTimeoutOrNull(8_000L) {
                    val entry = container.pendingEntryDao.getById(pendingId) ?: return@withTimeoutOrNull
                    if (action == ConfirmNotifier.ACTION_CONFIRM) {
                        EntryConfirmer.confirm(container, entry)
                    }
                    ConfirmNotifier.cancel(context, pendingId)
                    container.pendingEntryDao.deleteById(pendingId)
                }
            } finally {
                result.finish()
            }
        }
    }
}
