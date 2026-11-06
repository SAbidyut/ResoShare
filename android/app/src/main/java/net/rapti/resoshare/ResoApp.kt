package net.rapti.resoshare

import android.app.Application

class ResoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
