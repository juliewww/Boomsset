package com.boomsset

import android.app.Application
import com.boomsset.di.androidModule
import com.boomsset.di.initKoin

class BoomssetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(androidModule(this))
    }
}
