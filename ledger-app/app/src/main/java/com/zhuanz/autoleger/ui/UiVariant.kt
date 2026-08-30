package com.zhuanz.autoleger.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI 设计方案：五套可选的整体视觉（底部导航 + 首页头部 + 配色） */
enum class UiVariant {
    /** A：浮岛 Dock 导航 + 渐变英雄卡 */
    A,
    /** B：沉浸大标题 + 胶囊导航 */
    B,
    /** C：紧凑顶栏 + 彩色分区导航 */
    C,
    /** D：暗夜金属（强制深色） */
    D,
    /** E：暖橙活力 */
    E,
    /** F：简约卡片风（细描边、单色、留白） */
    F;

    val label: String
        get() = when (this) {
            A -> "A · 浮岛 Dock"
            B -> "B · 沉浸大标题"
            C -> "C · 彩色分区"
            D -> "D · 暗夜金属"
            E -> "E · 暖橙活力"
            F -> "F · 简约卡片"
        }

    companion object {
        fun fromIndex(i: Int): UiVariant = entries.getOrElse(i) { A }
    }
}

object UiVariantState {
    private const val PREFS = "settings"
    private const val KEY = "ui_variant"

    /** 预览模式：为 true 时界面实时应用 previewVariant，不写入偏好 */
    var previewing by mutableStateOf(false)
    var previewVariant by mutableStateOf(UiVariant.A)

    var current by mutableStateOf(UiVariant.A)
        private set

    /** 预览模式下的生效方案 */
    val effective: UiVariant get() = if (previewing) previewVariant else current

    fun load(context: Context) {
        // v2 键：简约卡片风成为默认方案
        val i = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY + "_v2", UiVariant.F.ordinal)
        current = UiVariant.fromIndex(i)
    }

    fun apply(context: Context, variant: UiVariant) {
        previewing = false
        current = variant
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY + "_v2", variant.ordinal).apply()
    }

    /** 进入/退出预览；进入时以当前方案为起点 */
    fun setPreview(on: Boolean) {
        if (on) previewVariant = current
        previewing = on
    }

    /** 预览中确认采用当前方案 */
    fun confirmPreview(context: Context) {
        previewing = false
        apply(context, previewVariant)
    }

    /** 预览中直接退出预览（不采用） */
    fun cancelPreview() {
        previewing = false
    }
}
