package dev.pohunek.companion.demo

import android.content.Context
import android.util.AtomicFile
import dev.pohunek.companion.R
import dev.pohunek.companion.config.RuntimeConfig
import dev.pohunek.companion.core.data.demo.DemoHostTransport
import dev.pohunek.companion.core.data.demo.DemoHosts
import dev.pohunek.companion.core.data.discovery.HostDiscovery
import dev.pohunek.companion.core.data.hosts.HostEdit
import dev.pohunek.companion.core.data.hosts.HostStore
import dev.pohunek.companion.core.data.hosts.HostStoreException
import dev.pohunek.companion.core.data.hosts.HostsRepository
import dev.pohunek.companion.core.data.hosts.HostsState
import dev.pohunek.companion.core.data.hosts.KnownHosts
import dev.pohunek.companion.core.data.layout.LayoutRepository
import dev.pohunek.companion.core.data.layout.LayoutStore
import dev.pohunek.companion.core.data.layout.RecentProjectsStore
import dev.pohunek.companion.core.data.live.HostLiveFactory
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.data.live.LastSessionsStore
import dev.pohunek.companion.core.data.live.LiveConfig
import dev.pohunek.companion.ioDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Qualifier of the demo's own singletons; nothing under it reads or writes the real hosts, layout or session files. */
val demoGraph = named("demo")

/** The demo's files live in this directory of the app's private storage, outside Auto Backup. */
private const val DEMO_DIRECTORY = "demo"

/**
 * Whether the demo is active. Process memory only, so a new process always starts outside the demo.
 */
class DemoMode(private val hosts: HostsRepository) {
    private val current = MutableStateFlow(false)

    val active: StateFlow<Boolean> get() = current

    fun leave() {
        current.value = false
    }

    /**
     * Adds the demo hosts that are not listed yet and marks the demo active. Returns the problem that stopped it, with the flag still
     * false, or null when the demo graph holds its two hosts.
     */
    suspend fun enter(): String? {
        hosts.load()
        val listed = (hosts.state.value as? HostsState.Loaded)?.hosts.orEmpty()
        // Hosts are added one by one and the first problem stops the rest, so a refused host leaves the demo inactive.
        val problem = try {
            DemoHosts.all.filterNot { it in listed }.firstNotNullOfOrNull { host ->
                when (val edit = hosts.add(host)) {
                    HostEdit.Saved -> null
                    is HostEdit.Refused -> edit.problem.message
                    is HostEdit.Unavailable -> edit.reason
                }
            }
        } catch (failure: HostStoreException) {
            failure.message
        }
        if (problem == null) current.value = true
        return problem
    }
}

/** The demo graph: the two demo hosts, their layout and last sessions, a registry whose loops serve the demo in-process, and no discovery. */
val demoModule = module {
    single(demoGraph) { HostsRepository(HostStore(demoFile("hosts.json"), get(ioDispatcher)), get()) }
    single(demoGraph) { LayoutRepository(LayoutStore(demoFile("layout.json"), get(ioDispatcher)), get()) }
    single(demoGraph) { RecentProjectsStore(demoFile("recent_projects.json"), get(ioDispatcher), get()) }
    single(demoGraph) { LastSessionsStore(demoFile("last_sessions.json"), get(ioDispatcher), get()) }
    // Never run: it only gives the demo hosts an empty discovery state, since the demo hosts have no overlay to discover through.
    single(demoGraph) {
        val runtime = get<RuntimeConfig>()
        HostDiscovery({ runtime.current().client }, { runtime.current().discovery }, get(ioDispatcher), get())
    }
    single(demoGraph) {
        val idle = get<Context>().resources.getInteger(R.integer.demo_idle_threshold_secs).seconds
        val runtime = get<RuntimeConfig>()
        HostRegistry(
            HostLiveFactory(
                { demoLive(runtime.current().live, idle) },
                TimeSource.Monotonic,
                get(),
                { route, _ -> DemoHostTransport(route) },
            ),
            get(demoGraph),
        )
    }
    single(demoGraph) { KnownHosts(get(demoGraph), get(demoGraph), get(demoGraph), discover = false) }
    single { DemoMode(get(demoGraph)) }
}

/** A file of the demo graph; Android's [AtomicFile] creates [DEMO_DIRECTORY] on the first write. */
private fun Scope.demoFile(name: String): AtomicFile = AtomicFile(File(File(get<Context>().noBackupFilesDir, DEMO_DIRECTORY), name))

/** The demo's live configuration: [config] with a shorter idle threshold, so the idle showcase sessions show as Idle within seconds. */
private fun demoLive(config: LiveConfig, idleThreshold: Duration): LiveConfig = LiveConfig(
    reseedInterval = config.reseedInterval,
    reconnectInitial = config.reconnectInitial,
    reconnectMax = config.reconnectMax,
    backoffReset = config.backoffReset,
    liveness = config.liveness,
    idleThreshold = idleThreshold,
    notificationPageSize = config.notificationPageSize,
)
