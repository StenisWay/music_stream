package dev.musicstream.feature.session.data.repository

import dev.musicstream.core.common.AppError
import dev.musicstream.core.network.ServerEndpointResolver
import dev.musicstream.core.network.di.ProbeClient
import dev.musicstream.feature.session.data.local.ConnectionStore
import dev.musicstream.feature.session.domain.model.ServerConnection
import dev.musicstream.feature.session.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Finds a working address for the home server.
 *
 * The server is never exposed to the internet: every candidate from pairing
 * is an internal address (home LAN, or a VPN address such as Tailscale
 * 100.x / WireGuard 10.x). Away from home the VPN makes them reachable, so
 * all candidates are treated the same: they are probed in parallel via
 * GET /healthz and the first one to answer wins.
 * The result is cached until [invalidate] (network change) or a failure.
 *
 * Also serves as the network layer's [ServerEndpointResolver]: OkHttp's
 * interceptor needs a plain suspend call, so [resolve] stays one and
 * [findServer] wraps it as a flow for the domain.
 */
@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    private val store: ConnectionStore,
    @ProbeClient private val probeClient: OkHttpClient,
) : ConnectionRepository, ServerEndpointResolver {
    private val mutex = Mutex()
    @Volatile private var resolvedBase: String? = null

    override val connection: Flow<ServerConnection?> = store.connection

    override fun save(connection: ServerConnection) = flow {
        store.save(connection)
        invalidate()
        emit(Unit)
    }

    override fun clear() = flow {
        store.clear()
        invalidate()
        emit(Unit)
    }

    override fun findServer(force: Boolean) = flow { emit(resolve(force)) }

    fun invalidate() {
        resolvedBase = null
    }

    override suspend fun resolve(force: Boolean): String {
        if (!force) resolvedBase?.let { return it }
        return mutex.withLock {
            if (!force) resolvedBase?.let { return it }
            val conn = store.current() ?: throw AppError.Invalid("server not paired")
            val base = probe(conn) ?: throw AppError.ServerUnreachable()
            resolvedBase = base
            base
        }
    }

    private suspend fun probe(conn: ServerConnection): String? = channelFlow {
        conn.hosts.distinct().forEach { host ->
            launch {
                val base = conn.baseUrl(host)
                if (healthy(base)) send(base)
            }
        }
    }.firstOrNull()

    /** Async call so cancelling the probe (another host won) also cancels the request. */
    private suspend fun healthy(base: String): Boolean = suspendCancellableCoroutine { cont ->
        val call = probeClient.newCall(Request.Builder().url(base + "healthz").build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resume(false)
            override fun onResponse(call: Call, response: Response) = response.use { cont.resume(it.isSuccessful) }
        })
    }
}
