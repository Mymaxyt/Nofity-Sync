package com.notifysync.app

import android.app.Application

class NotifySyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncEngine.get(this)
    }
}
