package dev.pohunek.companion

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.pohunek.companion.config.AppConfig
import dev.pohunek.companion.config.AppConfigResult
import dev.pohunek.companion.core.data.commands.SessionCommands
import dev.pohunek.companion.core.data.hosts.HostStore
import dev.pohunek.companion.core.data.hosts.HostsRepository
import dev.pohunek.companion.core.data.live.HostLiveFactory
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.data.log.JsonLineLog
import dev.pohunek.companion.core.data.log.LogMirror
import dev.pohunek.companion.core.data.log.RotatingLogFile
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.time.Clock
import kotlin.time.TimeSource

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
    single { HostsRepository(get(), get()) }
    single<AppLog> {
        val context = get<Context>()
        val config = validConfig(context)
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        JsonLineLog(
            RotatingLogFile(File(context.filesDir, LOG_DIRECTORY), LOG_FILE, config.logMaxBytes),
            get(),
            if (debuggable) LogMirror { level, _, line -> Log.println(level.priority(), LOGCAT_TAG, line) } else null,
        ) { failure -> Log.w(LOGCAT_TAG, "cannot write the log: ${failure.message}") }
    }
    // The process lifecycle: the live loops run only while the app is visible.
    single<Lifecycle> { ProcessLifecycleOwner.get().lifecycle }
    // One registry for the process: the session list and the hosts screen read the same loops.
    single {
        val config = validConfig(get())
        HostRegistry(HostLiveFactory(config.client, config.live, get(ioDispatcher), TimeSource.Monotonic, get()))
    }
    single { SessionCommands(validConfig(get()).client, get(ioDispatcher), get()) }
}

private fun validConfig(context: Context): AppConfig = (AppConfig.load(context.resources) as? AppConfigResult.Valid)?.config
    ?: error("this is requested only after the configuration was accepted")

private fun LogLevel.priority(): Int = when (this) {
    LogLevel.Debug -> Log.DEBUG
    LogLevel.Info -> Log.INFO
    LogLevel.Warn -> Log.WARN
    LogLevel.Error -> Log.ERROR
}
