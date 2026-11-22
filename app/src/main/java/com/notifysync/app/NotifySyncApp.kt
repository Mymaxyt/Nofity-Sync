package com.notifysync.app

import android.app.Application
import java.io.File

class NotifySyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Absturzursache für die Testphase sichern; sie erscheint beim nächsten Start im Protokoll.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(filesDir, CRASH_FILE).writeText(e.stackTraceToString().take(4000)) }
            previous?.uncaughtException(t, e)
        }
        val engine = SyncEngine.get(this)
        val crash = File(filesDir, CRASH_FILE)
        if (crash.exists()) {
            engine.logCrash(crash.readText())
            crash.delete()
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
