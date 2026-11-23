package io.github.ocmediasync

import android.app.Application
import io.github.ocmediasync.sync.Notifications
import io.github.ocmediasync.sync.SyncScheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        SyncScheduler.ensureScheduled(this)
    }
}
