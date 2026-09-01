package com.zhuanz.autoleger.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zhuanz.autoleger.BuildConfig
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.data.BackupManager
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 每 3 天自动备份到系统下载文件夹。
 * 备份文件不覆盖，每次生成新文件，即使 App 卸载也不会丢失。
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoBackup"
        private const val WORK_NAME = "auto_backup"
        private const val PREFS = "auto_backup"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_BACKUP = "last_backup_time"
        const val DEFAULT_INTERVAL_DAYS = 3L

        /** 调度自动备份 */
        fun schedule(context: Context, intervalDays: Long = DEFAULT_INTERVAL_DAYS) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresStorageNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalDays, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }

        /** 取消自动备份 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
            if (enabled) {
                schedule(context)
            } else {
                cancel(context)
            }
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as LedgerAppProvider
        val container = app.container

        try {
            val json = BackupManager.export(container)
            val fileName = "AutoLedger_备份_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())}.json"

            saveToDownloads(fileName, json)

            // 记录上次备份时间
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_BACKUP, System.currentTimeMillis())
                .apply()

            if (BuildConfig.DEBUG) Log.d(TAG, "备份成功：$fileName")
            return Result.success()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "备份失败", e)
            return Result.retry()
        }
    }

    private fun saveToDownloads(fileName: String, content: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+：使用 MediaStore.Downloads
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = applicationContext.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("无法创建下载文件")

            applicationContext.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray())
            } ?: throw Exception("无法写入文件")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            applicationContext.contentResolver.update(uri, values, null, null)
        } else {
            // 旧 API 回退到公共 Downloads 目录
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val file = java.io.File(dir, fileName)
            file.writeText(content)
        }
    }
}