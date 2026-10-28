package com.phisherman

import android.app.Application
import com.phisherman.link.LinkAnalyser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhishermanApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // load the model early so the first link tap is fast
        scope.launch { LinkAnalyser.init(this@PhishermanApp) }
    }
}
