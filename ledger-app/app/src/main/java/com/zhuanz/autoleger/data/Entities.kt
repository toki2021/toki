package com.zhuanz.autoleger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 账目类型 */
const val TYPE_EXPENSE = "EXPENSE"
const val TYPE_REFUND = "REFUND"

/** 记录来源 */
const val SOURCE_NOTIFICATION = "NOTIFICATION"
const val SOURCE_CSV = "CSV"
const val SOURCE_MANUAL = "MANUAL"

/** 待处理条目状态 */
const val PENDING_CONFIRM = "PENDING_CONFIRM"
const val PENDING_UNPARSED = "PENDING_UNPARSED"

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String = TYPE_EXPENSE,
    /** 单位：分 */
    val amountCents: Long,
    val merchant: String,
    val categoryId: Long?,
    /** epoch 毫秒 */
    val time: Long,
    val source: String,
    /** 退款冲销目标支出的 id，仅 type=REFUND 时有值 */
    val refundOf: Long? = null,
    /** 原始通知文本，便于追溯 */
    val rawText: String? = null,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "🏷️",
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 商户名关键词，包含匹配 */
    val pattern: String,
    val categoryId: Long,
    /** 数值越大优先级越高 */
    val priority: Int = 0,
)

@Entity(tableName = "pending_entries")
data class PendingEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    /** 解析出的金额（分），解析失败为 null */
    val amountCents: Long?,
    /** 解析出的商户名，解析失败为 null */
    val merchant: String?,
    val status: String,
    val time: Long,
)
