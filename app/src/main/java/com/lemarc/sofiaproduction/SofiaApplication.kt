package com.lemarc.sofiaproduction

import android.app.Application
import com.lemarc.sofiaproduction.data.AppSettings

class SofiaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }
}
