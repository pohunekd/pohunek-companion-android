// Koin start-up adapted from opencode-mobile's OpenCodeApplication (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion

import android.app.Application
import dev.pohunek.companion.core.data.di.dataModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PohunekApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PohunekApplication)
            modules(dataModule, appModule)
        }
    }
}
