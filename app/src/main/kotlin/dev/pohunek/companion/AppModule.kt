package dev.pohunek.companion

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import android.util.Log
import dev.pohunek.companion.config.AppConfig
import dev.pohunek.companion.config.AppConfigResult
import dev.pohunek.companion.core.data.hosts.HostStore
import dev.pohunek.companion.core.data.log.JsonLineLog
import dev.pohunek.companion.core.data.log.LogMirror
import dev.pohunek.companion.core.data.log.RotatingLogFile
import dev.pohunek.companion.core.data.sessions.HostSessions
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogLevel
import dev.pohunek.companion.protocol.ClientConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.time.Clock

/** Qualifier of the dispatcher that runs blocking I/O. */
val ioDispatcher = named("io")

/** Qualifier of the dispatcher that runs UI-thread work. */
val mainDispatcher = named("main")

/** App data lives in this file of the app's private files directory. */
private const val HOSTS_FILE = "hosts.json"

/** The structured log lives in this directory and file of the app's private files directory. */
private const val LOG_DIRECTORY = "logs"
private const val LOG_FILE = "app.log"
private const val LOGCAT_TAG = "Pohunek"

/** Koin bindings owned by the application shell. */
val appModule = module {
    single<CoroutineDispatcher>(ioDispatcher) { Dispatchers.IO }
    single<CoroutineDispatcher>(mainDispatcher) { Dispatchers.Main }
    single { HostStore(AtomicFile(File(get<Context>().filesDir, HOSTS_FILE)), get(ioDispatcher)) }
    single<Clock> { Clock.systemUTC() }
    single<AppLog> {
        val context = get<Context>()
        val config = (AppConfig.load(context.resources) as? AppConfigResult.Valid)?.config
            ?: error("the log is requested only after the configuration was accepted")
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        JsonLineLog(
            RotatingLogFile(File(context.filesDir, LOG_DIRECTORY), LOG_FILE, config.logMaxBytes),
            get(),
            if (debuggable) LogMirror { level, _, line -> Log.println(level.priority(), LOGCAT_TAG, line) } else null,
        ) { failure -> Log.w(LOGCAT_TAG, "cannot write the log: ${failure.message}") }
    }
    factory { (client: ClientConfig) -> HostSessions(client, get(ioDispatcher)) }
}

private fun LogLevel.priority(): Int = when (this) {
    LogLevel.Debug -> Log.DEBUG
    LogLevel.Info -> Log.INFO
    LogLevel.Warn -> Log.WARN
    LogLevel.Error -> Log.ERROR
}
