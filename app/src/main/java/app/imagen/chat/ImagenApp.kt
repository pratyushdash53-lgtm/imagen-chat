package app.imagen.chat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.imagen.chat.data.ChatStore
import app.imagen.chat.data.SettingsStore
import app.imagen.chat.gen.GenerationManager
import app.imagen.chat.gen.Notifications

class ImagenApp : Application() {
    lateinit var chats: ChatStore
        private set
    lateinit var manager: GenerationManager
        private set

    // Created lazily: encrypted preferences touch the keystore, which is slow at cold start.
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        chats = ChatStore(this)
        manager = GenerationManager(this, chats)
        Notifications.createChannels(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                manager.appInForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                manager.appInForeground = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // Housekeeping off the main thread: recover chats whose job died with the process, drop stray files.
        Thread {
            manager.recoverInterrupted()
            chats.cleanOrphans()
        }.start()
    }
}
