package com.zhuanz.autoleger.data

import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账目数据备份/恢复：将所有数据导出为单文件 JSON，支持从 JSON 恢复。
 * 使用内置 org.json 避免额外依赖。
 */
object BackupManager {

    private fun txToJson(tx: TransactionEntity): JSONObject = JSONObject().apply {
        put("id", tx.id)
        put("type", tx.type)
        put("amountCents", tx.amountCents)
        put("merchant", tx.merchant)
        put("categoryId", tx.categoryId ?: JSONObject.NULL)
        put("time", tx.time)
        put("source", tx.source)
        put("refundOf", tx.refundOf ?: JSONObject.NULL)
        put("rawText", tx.rawText ?: JSONObject.NULL)
    }

    private fun jsonToTx(obj: JSONObject): TransactionEntity = TransactionEntity(
        id = obj.optLong("id"),
        type = obj.optString("type", TYPE_EXPENSE),
        amountCents = obj.optLong("amountCents"),
        merchant = obj.optString("merchant", ""),
        categoryId = obj.optLong("categoryId", -1).takeIf { it >= 0 },
        time = obj.optLong("time"),
        source = obj.optString("source", SOURCE_MANUAL),
        refundOf = obj.optLong("refundOf", -1).takeIf { it >= 0 },
        rawText = obj.optString("rawText").ifBlank { null },
    )

    private fun catToJson(cat: CategoryEntity): JSONObject = JSONObject().apply {
        put("id", cat.id)
        put("name", cat.name)
        put("emoji", cat.emoji)
    }

    private fun jsonToCat(obj: JSONObject): CategoryEntity = CategoryEntity(
        id = obj.optLong("id"),
        name = obj.optString("name", ""),
        emoji = obj.optString("emoji", "🏷️"),
    )

    private fun pendToJson(p: PendingEntryEntity): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("packageName", p.packageName)
        put("title", p.title)
        put("text", p.text)
        put("amountCents", p.amountCents ?: JSONObject.NULL)
        put("merchant", p.merchant ?: JSONObject.NULL)
        put("status", p.status)
        put("time", p.time)
    }

    private fun jsonToPend(obj: JSONObject): PendingEntryEntity = PendingEntryEntity(
        id = obj.optLong("id"),
        packageName = obj.optString("packageName", ""),
        title = obj.optString("title", ""),
        text = obj.optString("text", ""),
        amountCents = obj.optLong("amountCents", -1).takeIf { it >= 0 },
        merchant = obj.optString("merchant").ifBlank { null },
        status = obj.optString("status", PENDING_CONFIRM),
        time = obj.optLong("time"),
    )

    suspend fun export(container: AppContainer): String {
        val txs = container.transactionDao.observeAll().first()
        val cats = container.categoryDao.observeAll().first()
        val pends = container.pendingEntryDao.observeAll().first()

        return JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("transactions", JSONArray(txs.map { txToJson(it) }))
            put("categories", JSONArray(cats.map { catToJson(it) }))
            put("pendingEntries", JSONArray(pends.map { pendToJson(it) }))
        }.toString(2)
    }

    suspend fun importJson(container: AppContainer, jsonString: String): String {
        val root = JSONObject(jsonString)
        val txs = root.getJSONArray("transactions")
        val cats = root.getJSONArray("categories")
        val pends = root.optJSONArray("pendingEntries") ?: JSONArray()

        // 清除旧数据
        container.transactionDao.deleteAll()
        container.categoryDao.deleteAll()
        container.pendingEntryDao.deleteAll()

        // 写入
        for (i in 0 until cats.length()) container.categoryDao.insert(jsonToCat(cats.getJSONObject(i)))
        for (i in 0 until txs.length()) container.transactionDao.insert(jsonToTx(txs.getJSONObject(i)))
        for (i in 0 until pends.length()) container.pendingEntryDao.insert(jsonToPend(pends.getJSONObject(i)))

        return "恢复完成：${txs.length()} 笔账单、${cats.length()} 个分类、${pends.length()} 条待处理"
    }
}