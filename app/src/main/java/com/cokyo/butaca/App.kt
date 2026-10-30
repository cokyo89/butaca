package com.cokyo.butaca

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        TorrentEngine.init(this)
    }
}
