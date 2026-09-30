package com.zhuanz.autoleger.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhuanz.autoleger.MainActivity
import com.zhuanz.autoleger.LedgerAppProvider
import com.zhuanz.autoleger.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class LedgerInteractionTest {
    @get:Rule(order = 0) val permissions = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun monthPersistsAcrossNavigationAndRecreation() {
        val previous = YearMonth.now().minusMonths(1)
        val label = "${previous.year}年${previous.monthValue}月"
        compose.onNodeWithContentDescription("上个月").performClick()
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("统计", useUnmergedTree = true).performClick()
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("账单", useUnmergedTree = true).performClick()
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("本月").performClick()
    }

    @Test fun rulesAndCategoriesRemainReachable() {
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("自动分类规则").assertIsDisplayed().performClick()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("管理分类").assertIsDisplayed().performClick()
    }

    @Test fun manuallyAddAndEditAmount() {
        val container = (compose.activity.applicationContext as LedgerAppProvider).container
        compose.onAllNodesWithText("记一笔").onFirst().performClick()
        compose.onNodeWithContentDescription("输入1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("输入2").performClick()
        compose.onNodeWithContentDescription("输入.").performClick()
        compose.onNodeWithContentDescription("输入3").performClick()
        compose.onNodeWithContentDescription("输入4").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("新增编辑流程测试商户")
        compose.onNodeWithText("保存", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("新增编辑流程测试商户").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("新增编辑流程测试商户").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("25.67")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { container.transactionDao.observeAll().first().any { it.merchant == "新增编辑流程测试商户" && it.amountCents == 2567L } }
        }
        runBlocking {
            container.transactionDao.observeAll().first().filter { it.merchant == "新增编辑流程测试商户" }.forEach { container.transactionDao.deleteById(it.id) }
        }
    }

    @Test fun pendingConfirmationCreatesLedgerEntry() {
        val container = (compose.activity.applicationContext as LedgerAppProvider).container
        val id = runBlocking {
            container.pendingEntryDao.insert(PendingEntryEntity(packageName = "com.tencent.mm", title = "微信支付",
                text = "支付成功 ¥18.50 待确认流程测试商户", amountCents = 1850, merchant = "待确认流程测试商户",
                status = PENDING_CONFIRM, time = System.currentTimeMillis()))
        }
        compose.onNodeWithText("待确认", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("待确认流程测试商户").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看原通知").performClick()
        compose.onNodeWithText("微信支付\n支付成功 ¥18.50 待确认流程测试商户").assertExists()
        compose.onNodeWithText("确认入账").performClick()
        compose.waitUntil(10_000) { runBlocking { container.pendingEntryDao.getById(id) == null } }
        compose.onNodeWithText("账单", useUnmergedTree = true).performClick()
        compose.onNodeWithText("待确认流程测试商户").assertExists()
        runBlocking {
            container.transactionDao.observeAll().first().filter { it.merchant == "待确认流程测试商户" }.forEach { container.transactionDao.deleteById(it.id) }
        }
    }

    @Test fun pendingIntentIsHandledWhenActivityHasSavedState() {
        val container = (compose.activity.applicationContext as LedgerAppProvider).container
        val id = runBlocking {
            container.pendingEntryDao.insert(PendingEntryEntity(packageName = "com.tencent.mm", title = "微信支付",
                text = "支付成功 ¥18.50 通知跳转测试商户", amountCents = 1850, merchant = "通知跳转测试商户",
                status = PENDING_CONFIRM, time = System.currentTimeMillis()))
        }
        compose.activityRule.scenario.onActivity {
            it.intent.putExtra(com.zhuanz.autoleger.notify.ConfirmNotifier.EXTRA_PENDING_ID, id)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("通知跳转测试商户").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("通知跳转测试商户").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("账单", useUnmergedTree = true).assertExists()
        runBlocking { container.pendingEntryDao.deleteById(id) }
    }

    @Test fun deleteAndUndoRestoreSameTransaction() {
        val container = (compose.activity.applicationContext as LedgerAppProvider).container
        val id = runBlocking {
            container.transactionDao.insert(TransactionEntity(amountCents = 1234, merchant = "撤销流程测试商户",
                categoryId = null, time = System.currentTimeMillis(), source = SOURCE_MANUAL))
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("撤销流程测试商户").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("撤销流程测试商户").performClick()
        compose.onNodeWithText("删除这笔账单").performScrollTo().performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.onNodeWithText("撤销").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { runBlocking { container.transactionDao.getById(id) != null } }
        compose.onNodeWithText("撤销流程测试商户").assertExists()
        runBlocking { container.transactionDao.deleteById(id) }
    }
}
