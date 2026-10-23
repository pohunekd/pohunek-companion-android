package dev.pohunek.companion

import android.content.Context
import android.util.AtomicFile
import dev.pohunek.companion.core.data.hosts.HostStore
import dev.pohunek.companion.core.data.sessions.HostSessions
import dev.pohunek.companion.protocol.ClientConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

/** Qualifier of the dispatcher that runs blocking I/O. */
val ioDispatcher = named("io")

/** App data lives in this file of the app's private files directory. */
private const val HOSTS_FILE = "hosts.json"

/** Koin bindings owned by the application shell. */
val appModule = module {
    single<CoroutineDispatcher>(ioDispatcher) { Dispatchers.IO }
    single { HostStore(AtomicFile(File(get<Context>().filesDir, HOSTS_FILE)), get(ioDispatcher)) }
    factory { (client: ClientConfig) -> HostSessions(client, get(ioDispatcher)) }
}
