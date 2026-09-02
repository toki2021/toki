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

/** 处理流动云胶囊上的"重新识别"按钮 */
class FluidCloudReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingId = intent.getLongExtra(FluidCloudService.EXTRA_PENDING_ID, -1)
        if (pendingId < 0) return
        // 点了"重新识别"立即自动收起通知栏，避免遮挡支付页
        try {
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {
            // 个别 ROM 限制该系统广播，忽略即可
        }
        val container = (context.applicationContext as LedgerAppProvider).container

        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000L) {
                    val entry = container.pendingEntryDao.getById(pendingId) ?: return@withTimeoutOrNull
                    when (EntryConfirmer.reRecognize(container, entry)) {
                        is EntryConfirmer.ReRecognizeResult.Updated -> {
                            // 更新前台通知（刷新胶囊内容）
                            FluidCloudService.stop(context)
                            FluidCloudService.start(context)
                        }
                        is EntryConfirmer.ReRecognizeResult.NoChange -> {
                            // 原文解析不出新信息，不做任何变化
                        }
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}