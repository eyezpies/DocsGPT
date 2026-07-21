package com.docsgpt.android

import android.app.Application
import com.docsgpt.android.di.AppContainer

class DocsGptApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
