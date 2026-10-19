package dev.sash.app

import android.app.Application
import dev.sash.app.security.AppLock
import dev.sash.app.stream.Controller

class App : Application() {
    lateinit var controller: Controller
        private set
    lateinit var lock: AppLock
        private set

    override fun onCreate() {
        super.onCreate()
        controller = Controller(this)
        lock = AppLock(this)
    }
}
