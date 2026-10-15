package dev.remotemac.app

import android.app.Application
import dev.remotemac.app.stream.Controller

class App : Application() {
    lateinit var controller: Controller
        private set

    override fun onCreate() {
        super.onCreate()
        controller = Controller(this)
    }
}
