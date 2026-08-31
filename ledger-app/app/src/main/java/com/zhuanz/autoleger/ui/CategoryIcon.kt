package com.zhuanz.autoleger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DirectionsTransit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 分类名 → 矢量图标 + 固定柔和配色（浅色底 + 同色系深图标，夜间自动换深底浅图标） */
object CategoryIconMapper {
    data class ColorPair(val bgLight: Color, val fgLight: Color, val bgDark: Color, val fgDark: Color)

    data class Spec(
        val keywords: List<String>,
        val icon: ImageVector,
        val colors: ColorPair,
    )

    private val specs = listOf(
        Spec(listOf("餐饮", "食", "饮", "餐", "外卖", "小吃", "咖啡", "零食"),
            Icons.Rounded.Restaurant,
            ColorPair(Color(0xFFFFF0DC), Color(0xFFC47B1D), Color(0xFF3D2E17), Color(0xFFF0BE71))),
        Spec(listOf("交通", "车", "加油", "地铁", "出"),
            Icons.Rounded.DirectionsTransit,
            ColorPair(Color(0xFFE1EAFF), Color(0xFF2E5FC4), Color(0xFF1C2A47), Color(0xFF9DBDF5))),
        Spec(listOf("购", "商", "超"),
            Icons.Rounded.ShoppingBag,
            ColorPair(Color(0xFFDFF3E6), Color(0xFF22854C), Color(0xFF173325), Color(0xFF7FD9A6))),
        Spec(listOf("居", "住", "房", "水电"),
            Icons.Rounded.Home,
            ColorPair(Color(0xFFF2E9DD), Color(0xFF8A5A2B), Color(0xFF383023), Color(0xFFD9B98A))),
        Spec(listOf("娱", "游戏", "影", "音"),
            Icons.Rounded.SportsEsports,
            ColorPair(Color(0xFFEEE6FF), Color(0xFF7143D6), Color(0xFF2B2347), Color(0xFFC3A9F5))),
        Spec(listOf("医", "药", "疗", "诊"),
            Icons.Rounded.MedicalServices,
            ColorPair(Color(0xFFFFE3E3), Color(0xFFC43B3B), Color(0xFF47201F), Color(0xFFF5A5A5))),
        Spec(listOf("通讯", "话费", "网络"),
            Icons.Rounded.PhoneIphone,
            ColorPair(Color(0xFFDFF3F2), Color(0xFF0E8078), Color(0xFF123A37), Color(0xFF7ADBD2))),
        Spec(listOf("其他"),
            Icons.Rounded.Category,
            ColorPair(Color(0xFFEDEDF0), Color(0xFF5F5F68), Color(0xFF26262B), Color(0xFFABABB4))),
    )

    private val fallback = ColorPair(
        Color(0xFFF1F1F3), Color(0xFF55555C), Color(0xFF26262B), Color(0xFFABABB4)
    )

    fun specFor(name: String): Spec =
        specs.firstOrNull { s -> s.keywords.any { name.contains(it) } }
            ?: Spec(emptyList(), Icons.AutoMirrored.Rounded.ReceiptLong, fallback)

    fun iconFor(name: String): ImageVector = specFor(name).icon

    fun colorsFor(name: String, dark: Boolean): ColorPair {
        val c = specFor(name).colors
        return if (dark) ColorPair(c.bgDark, c.fgDark, c.bgDark, c.fgDark)
        else ColorPair(c.bgLight, c.fgLight, c.bgLight, c.fgLight)
    }

    fun colorIndexFor(name: String): Int = ((name.hashCode() % 4) + 4) % 4
}

/** 圆形浅底 + 单色矢量图标的分类图标（苹果风统一视觉） */
@Composable
fun CategoryIcon(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = CategoryIconMapper.colorsFor(name, dark)
    Box(
        modifier
            .size(size)
            .background(colors.bgLight, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            CategoryIconMapper.iconFor(name),
            contentDescription = name,
            tint = colors.fgLight,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** 主题感知的分类衬色（用于图表配色） */
@Composable
fun categoryPalette(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    Color(0xFF8E7CC3),
    Color(0xFFCC8B6E),
    Color(0xFF6E9FCC),
    Color(0xFFB58FC9),
    MaterialTheme.colorScheme.onSurfaceVariant,
)
