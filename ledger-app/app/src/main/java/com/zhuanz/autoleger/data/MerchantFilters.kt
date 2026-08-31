package com.zhuanz.autoleger.data

import android.content.Context
import org.json.JSONObject

/**
 * 泛称/占位商户名的集中来源。
 *
 * 这类商户名（"微信支付""支付宝""未知商户"等）没有信息量，读屏补全和
 * 历史联想都应跳过它们。此前多处各自硬编码同一份名单，改一处漏一处。
 * 现在统一从这里取：优先读 assets/merchant_filters.json（随版本可更新），
 * 缺失或解析失败时回退到内置默认，加载一次后缓存。
 */
object MerchantFilters {

    private const val ASSET_FILE = "merchant_filters.json"

    /** 内置默认，作为 assets 缺失/解析失败时的回退 */
    val DEFAULT_GENERIC_MERCHANTS = setOf("微信支付", "支付宝", "未知商户", "已支付", "已付款")

    @Volatile
    private var cached: Set<String>? = null

    /** 泛称商户名集合；首次调用从 assets 加载并缓存，线程安全 */
    fun genericMerchants(context: Context): Set<String> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(context).also { cached = it }
        }
    }

    private fun load(context: Context): Set<String> = try {
        val raw = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        val arr = JSONObject(raw).optJSONArray("generic_merchants")
            ?: return DEFAULT_GENERIC_MERCHANTS
        val parsed = (0 until arr.length())
            .map { arr.optString(it).trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        parsed.ifEmpty { DEFAULT_GENERIC_MERCHANTS }
    } catch (e: Exception) {
        DEFAULT_GENERIC_MERCHANTS
    }
}