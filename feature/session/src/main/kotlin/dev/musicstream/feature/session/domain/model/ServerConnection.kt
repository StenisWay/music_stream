package dev.musicstream.feature.session.domain.model

import java.security.MessageDigest
import java.util.Base64

/**
 * Everything needed to reach and trust the home server. Created from the
 * connection code printed by `musicserver` ("MS1:<base64url JSON>").
 */
data class ServerConnection(
    /** Candidate hosts: home LAN and VPN addresses of the server. */
    val hosts: List<String>,
    val port: Int,
    val scheme: String,
    /** "sha256/<base64>" SPKI pin of the server certificate (null for plain http). */
    val certPin: String?,
    /** Server public key (base64 DER SubjectPublicKeyInfo); its SHA-256 is [certPin]. */
    val publicKey: String? = null,
) {
    init {
        require(hosts.isNotEmpty()) { "at least one host is required" }
        require(port in 1..65535) { "invalid port $port" }
        require(scheme == "https" || scheme == "http") { "invalid scheme $scheme" }
    }

    fun baseUrl(host: String): String = "$scheme://${formatHost(host)}:$port/"

    private fun formatHost(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host
}

/** Parses connection codes. Pure Kotlin so it is unit-testable on the JVM. */
object ConnectionCode {
    private const val PREFIX = "MS1:"
    private val PIN = Regex("^sha256/[A-Za-z0-9+/]{43}=$")

    fun parse(code: String): ServerConnection {
        val trimmed = code.trim()
        require(trimmed.startsWith(PREFIX)) { "not a MusicStream connection code" }
        val json = String(Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX)), Charsets.UTF_8)
        val fields = MiniJson.parseObject(json)
        val pin = fields["pin"] as? String
        require(pin == null || PIN.matches(pin)) { "invalid certificate pin" }
        val publicKey = fields["pk"] as? String
        if (publicKey != null) {
            val spki = runCatching { Base64.getDecoder().decode(publicKey) }.getOrNull()
            require(spki != null && pin != null && pinOf(spki) == pin) { "public key does not match the certificate pin" }
        }
        @Suppress("UNCHECKED_CAST")
        return ServerConnection(
            hosts = (fields["hosts"] as? List<Any?>).orEmpty().filterIsInstance<String>().filter { it.isNotBlank() },
            port = (fields["port"] as? Number)?.toInt() ?: 0,
            scheme = fields["scheme"] as? String ?: "https",
            certPin = pin,
            publicKey = publicKey,
        )
    }

    private fun pinOf(spki: ByteArray): String =
        "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))

    /** Manual setup: "host[:port]" plus an optional pin. */
    fun manual(hostAndPort: String, certPin: String?): ServerConnection {
        val value = hostAndPort.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val host = value.substringBeforeLast(':', value)
        val port = value.substringAfterLast(':', "").toIntOrNull() ?: DEFAULT_PORT
        val pin = certPin?.trim()?.takeIf { it.isNotEmpty() }
        require(pin == null || PIN.matches(pin)) { "invalid certificate pin" }
        return ServerConnection(listOf(host), port, if (pin != null) "https" else "http", pin)
    }

    const val DEFAULT_PORT = 8443
}

/**
 * Minimal JSON reader for the connection code (objects, arrays, strings,
 * numbers, booleans, null). Keeps the domain module free of serialization
 * libraries.
 */
internal object MiniJson {
    fun parseObject(s: String): Map<String, Any?> {
        val p = Parser(s)
        val v = p.value()
        p.skipWs()
        require(p.i == s.length && v is Map<*, *>) { "invalid JSON" }
        @Suppress("UNCHECKED_CAST")
        return v as Map<String, Any?>
    }

    private class Parser(val s: String) {
        var i = 0
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            skipWs()
            require(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "invalid literal" }
            i += word.length
            return v
        }
        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs(); require(s[i] == ':'); i++
                m[k] = value()
                skipWs()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("invalid object") }
            }
        }
        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                skipWs()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> error("invalid array") }
            }
        }
        private fun str(): String {
            require(s[i] == '"') { "expected string" }
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { sb.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            }
            i++
            return sb.toString()
        }
        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
