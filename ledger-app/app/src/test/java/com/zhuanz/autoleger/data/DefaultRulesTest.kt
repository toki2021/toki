package com.zhuanz.autoleger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultRulesTest {

    @Test
    fun 餐饮品牌() {
        listOf("美团外卖-国贸店", "肯德基", "瑞幸咖啡(国贸店)", "蜜雪冰城", "饿了么", "沙县小吃")
            .forEach { assertEquals(it, "餐饮", DefaultRules.matchCategoryName(it)) }
    }

    @Test
    fun 交通品牌_优先于泛词() {
        // 美团单车必须命中交通而不是被"美团→餐饮"截胡
        assertEquals("交通", DefaultRules.matchCategoryName("美团单车"))
        assertEquals("交通", DefaultRules.matchCategoryName("滴滴出行"))
        assertEquals("交通", DefaultRules.matchCategoryName("中国石化加油站"))
        assertEquals("交通", DefaultRules.matchCategoryName("北京地铁"))
    }

    @Test
    fun 购物品牌() {
        listOf("京东商城", "山姆会员店", "优衣库", "小米之家", "百果园")
            .forEach { assertEquals(it, "购物", DefaultRules.matchCategoryName(it)) }
    }

    @Test
    fun 居住娱乐医疗通讯() {
        assertEquals("居住", DefaultRules.matchCategoryName("物业费"))
        assertEquals("居住", DefaultRules.matchCategoryName("全季酒店"))
        assertEquals("娱乐", DefaultRules.matchCategoryName("腾讯视频会员"))
        assertEquals("医疗", DefaultRules.matchCategoryName("老百姓大药房"))
        assertEquals("通讯", DefaultRules.matchCategoryName("中国移动话费充值"))
    }

    @Test
    fun 个人转账_无规则命中() {
        assertNull(DefaultRules.matchCategoryName("tokizero"))
        assertNull(DefaultRules.matchCategoryName("杭州深度求索"))
        assertNull(DefaultRules.matchCategoryName("张三"))
    }

    @Test
    fun 大小写不敏感() {
        assertEquals("购物", DefaultRules.matchCategoryName("apple store"))
        assertEquals("娱乐", DefaultRules.matchCategoryName("bilibili"))
    }

    @Test
    fun 规则表无重复关键词() {
        val keys = DefaultRules.RULES.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
    }
}
