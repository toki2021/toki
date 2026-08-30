package com.zhuanz.autoleger

import android.app.Application
import com.zhuanz.autoleger.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface LedgerAppProvider {
    val container: AppContainer
}

class LedgerApp : Application(), LedgerAppProvider {

    override lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { container.seedDefaultCategoriesIfNeeded() }
    }
}
