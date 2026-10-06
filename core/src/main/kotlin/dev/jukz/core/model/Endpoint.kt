package dev.jukz.core.model

/** A reachable host:port a guest connects to (directly, hole-punched, or via relay). */
data class Endpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }

    /** `host:port`, with an IPv6 host in brackets (`[2001:db8::1]:25565`). */
    fun format(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(s: String): Endpoint {
            if (s.startsWith("[")) {
                val close = s.indexOf("]:")
                require(close > 1) { "invalid endpoint: $s" }
                val port = s.substring(close + 2).toIntOrNull()
                    ?: throw IllegalArgumentException("invalid port in endpoint: $s")
                return Endpoint(s.substring(1, close), port)
            }
            val idx = s.lastIndexOf(':')
            require(idx in 1 until s.length - 1) { "invalid endpoint: $s" }
            val host = s.substring(0, idx)
            val port = s.substring(idx + 1).toIntOrNull()
                ?: throw IllegalArgumentException("invalid port in endpoint: $s")
            return Endpoint(host, port)
        }
    }
}
