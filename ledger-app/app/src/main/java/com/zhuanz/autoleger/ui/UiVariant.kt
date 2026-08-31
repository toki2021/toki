package com.zhuanz.autoleger.ui

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
