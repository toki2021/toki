package com.zhuanz.autoleger.data

import java.math.BigDecimal

/**
 * 将金额文本（单位：元）安全地转换为分。
 *
 * 使用 [BigDecimal] 文本路径，绕开 double 的二进制浮点误差：
 * `Math.round(19.99.toDouble() * 100)` 之类会返回 1998 而不是 1999。
 *
 * 只接受恰好两位小数以内（货币场景），无法解析或超过两位小数返回 null，
 * 由调用方决定是否拒绝/补录。
 */
fun String.toCents(): Long? {
    val dec = trim().toBigDecimalOrNull() ?: return null
    return try {
        dec.movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}
