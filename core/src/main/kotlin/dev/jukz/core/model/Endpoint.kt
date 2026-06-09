package dev.jukz.core.model

/** A reachable host:port a guest connects to (directly, hole-punched, or via relay). */
data class Endpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }

    fun format(): String = "$host:$port"

    companion object {
        fun parse(s: String): Endpoint {
            val idx = s.lastIndexOf(':')
            require(idx in 1 until s.length - 1) { "invalid endpoint: $s" }
            val host = s.substring(0, idx)
            val port = s.substring(idx + 1).toIntOrNull()
                ?: throw IllegalArgumentException("invalid port in endpoint: $s")
            return Endpoint(host, port)
        }
    }
}
