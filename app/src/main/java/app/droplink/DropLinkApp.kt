package app.droplink

import android.app.Application
import app.droplink.di.AppContainer
import app.droplink.service.Notifications

class DropLinkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        app.droplink.util.DiagLog.init(this)
        app.droplink.core.transfer.CoreLog.sink = { msg, err -> app.droplink.util.DiagLog.log(msg, err) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            app.droplink.util.DiagLog.log("CRASH in thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        container = AppContainer(this)
        Notifications.ensureChannels(this)
    }
}
