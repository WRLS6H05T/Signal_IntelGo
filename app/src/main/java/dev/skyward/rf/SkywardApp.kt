package dev.skyward.rf

import android.app.Application
import dev.skyward.rf.core.CrashLog

class SkywardApp : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = Container(this)
    }

    override fun onTerminate() {
        container.shutdown()
        super.onTerminate()
    }
}
