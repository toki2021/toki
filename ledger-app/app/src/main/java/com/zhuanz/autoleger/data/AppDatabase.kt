package com.zhuanz.autoleger.data

import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.first

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        RuleEntity::class,
        PendingEntryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun pendingEntryDao(): PendingEntryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "autoleger.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

/** 默认分类，首次启动时种子数据 */
val DEFAULT_CATEGORIES = listOf(
    "餐饮" to "🍜",
    "交通" to "🚇",
    "购物" to "🛍️",
    "居住" to "🏠",
    "娱乐" to "🎮",
    "医疗" to "💊",
    "通讯" to "📱",
    "其他" to "❓",
)

/** 应用级依赖容器，单例持有数据库与仓库 */
class AppContainer(context: Context) {
    val appContext = context.applicationContext
    val db: AppDatabase = AppDatabase.build(context)
    val transactionDao = db.transactionDao()
    val categoryDao = db.categoryDao()
    val ruleDao = db.ruleDao()
    val pendingEntryDao = db.pendingEntryDao()

    /** 交易仓库：ViewModel 应通过它而非 DAO 直接读写 */
    val transactionRepository: TransactionRepository = TransactionRepository(transactionDao)

    /** 首次启动写入默认分类 */
    suspend fun seedDefaultCategoriesIfNeeded() {
        if (categoryDao.count() == 0) {
            DEFAULT_CATEGORIES.forEach { (name, emoji) ->
                categoryDao.insert(CategoryEntity(name = name, emoji = emoji))
            }
        }
        seedDefaultRulesIfNeeded()
    }

    /**
     * 内置规则库一次性种入。已有规则（用户手建的）保持更高优先级，
     * 内置规则排在其后作为兜底库。
     */
    private suspend fun seedDefaultRulesIfNeeded() {
        val prefs = appContext.getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("default_rules_seeded", false)) return
        val catByName = categoryDao.observeAll().first().associate { it.name to it.id }
        val base = (ruleDao.maxPriority() ?: 0)
        DefaultRules.RULES.forEachIndexed { i, (pattern, catName) ->
            val catId = catByName[catName] ?: return@forEachIndexed
            ruleDao.insert(RuleEntity(pattern = pattern, categoryId = catId, priority = base - i))
        }
        prefs.edit().putBoolean("default_rules_seeded", true).apply()
    }

    /**
     * 按规则库给商户匹配分类；未命中返回 null（由调用方落到"其他"）。
     * 规则已按 priority DESC 排序，第一个包含匹配即命中。
     */
    suspend fun matchCategory(merchant: String): CategoryEntity? {
        if (merchant.isBlank()) return null
        for (rule in ruleDao.observeAll().first()) {
            if (merchant.contains(rule.pattern, ignoreCase = true)) {
                return categoryDao.getById(rule.categoryId)
            }
        }
        return null
    }
}
