package dev.pohunek.companion

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.pohunek.companion.config.RuntimeConfig
import dev.pohunek.companion.config.SETTINGS_FILE
import dev.pohunek.companion.core.data.actions.SessionActionRunner
import dev.pohunek.companion.core.data.commands.SessionCommands
import dev.pohunek.companion.core.data.discovery.HostDiscovery
import dev.pohunek.companion.core.data.hosts.HostStore
import dev.pohunek.companion.core.data.hosts.HostsRepository
import dev.pohunek.companion.core.data.hosts.KnownHosts
import dev.pohunek.companion.core.data.layout.LayoutRepository
import dev.pohunek.companion.core.data.layout.LayoutStore
import dev.pohunek.companion.core.data.live.HostLiveFactory
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.data.log.JsonLineLog
import dev.pohunek.companion.core.data.log.LogMirror
import dev.pohunek.companion.core.data.log.RotatingLogFile
import dev.pohunek.companion.core.data.newsession.NewSessions
import dev.pohunek.companion.core.data.newsession.PendingPlacementBook
import dev.pohunek.companion.core.data.settings.SettingsStore
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

/** The hosts live in this file of the app's private files directory. */
private const val HOSTS_FILE = "hosts.json"
private const val LAYOUT_FILE = "layout.json"

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
    single { LayoutStore(AtomicFile(File(get<Context>().filesDir, LAYOUT_FILE)), get(ioDispatcher)) }
    single { LayoutRepository(get(), get()) }
    single { SettingsStore(AtomicFile(File(get<Context>().filesDir, SETTINGS_FILE)), get(ioDispatcher)) }
    // One source for the tunables: consumers read the value in force whenever they use it.
    single { RuntimeConfig(get<Context>().resources, get()) }
    single<AppLog> {
        val context = get<Context>()
        val runtime = get<RuntimeConfig>()
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        JsonLineLog(
            RotatingLogFile(File(context.filesDir, LOG_DIRECTORY), LOG_FILE) { runtime.logMaxBytes() },
            get(),
            if (debuggable) LogMirror { level, _, line -> Log.println(level.priority(), LOGCAT_TAG, line) } else null,
        ) { failure -> Log.w(LOGCAT_TAG, "cannot write the log: ${failure.message}") }
    }
    // The process lifecycle: the live loops run only while the app is visible.
    single<Lifecycle> { ProcessLifecycleOwner.get().lifecycle }
    // One registry for the process: the session list and the hosts screen read the same loops.
    single {
        val runtime = get<RuntimeConfig>()
        HostRegistry(HostLiveFactory({ runtime.current().client }, { runtime.current().live }, get(ioDispatcher), TimeSource.Monotonic, get()))
    }
    // One discovery for the process: the hosts screen shows what the session list's foreground period found.
    single {
        val runtime = get<RuntimeConfig>()
        HostDiscovery({ runtime.current().client }, { runtime.current().discovery }, get(ioDispatcher), get())
    }
    single { KnownHosts(get(), get(), get()) }
    single {
        val runtime = get<RuntimeConfig>()
        SessionCommands({ runtime.current().client }, get(ioDispatcher), get())
    }
    single {
        val runtime = get<RuntimeConfig>()
        NewSessions({ runtime.current().client }, { runtime.current().newSession.client }, get(ioDispatcher), get())
    }
    single {
        val runtime = get<RuntimeConfig>()
        PendingPlacementBook(get()) { runtime.current().newSession.pendingPlacementMaxAge }
    }
    single {
        val runtime = get<RuntimeConfig>()
        SessionActionRunner({ runtime.current().actionClient }, get(ioDispatcher), get())
    }
}

private fun LogLevel.priority(): Int = when (this) {
    LogLevel.Debug -> Log.DEBUG
    LogLevel.Info -> Log.INFO
    LogLevel.Warn -> Log.WARN
    LogLevel.Error -> Log.ERROR
}
