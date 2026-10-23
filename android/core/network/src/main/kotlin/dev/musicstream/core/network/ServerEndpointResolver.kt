package dev.musicstream.core.network

/**
 * Port that turns [ServerUrls.VIRTUAL_HOST] into a reachable base URL.
 * Implemented by the session feature, which owns pairing and address probing;
 * the network layer only needs the answer.
 */
interface ServerEndpointResolver {
    /** Returns the base URL ("https://host:port/") of a reachable server address. */
    suspend fun resolve(force: Boolean): String
}
