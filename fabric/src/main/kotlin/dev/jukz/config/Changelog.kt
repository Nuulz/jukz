package dev.jukz.config

/**
 * CHANGELOG.md (bundled at assets/jukz/CHANGELOG.md): `## X.Y.Z` sections of `- ` bullets, newest first.
 */
object Changelog {
    class Section(val version: String, val bullets: List<String>)

    fun bundled(): List<Section> =
        Changelog::class.java.getResourceAsStream("/assets/jukz/CHANGELOG.md")
            ?.use { parse(it.readBytes().decodeToString()) } ?: emptyList()

    fun parse(text: String): List<Section> {
        val sections = mutableListOf<Section>()
        var version: String? = null
        val bullets = mutableListOf<String>()
        fun flush() {
            version?.let { sections += Section(it, bullets.toList()) }
            bullets.clear()
        }
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("## ") -> {
                    flush()
                    version = trimmed.removePrefix("## ").trim().removePrefix("[").substringBefore(']').substringBefore(' ')
                }
                version != null && trimmed.startsWith("- ") -> bullets += trimmed.removePrefix("- ")
                version != null && trimmed.isNotEmpty() && bullets.isNotEmpty() -> bullets[bullets.lastIndex] += " $trimmed" // wrapped bullet
            }
        }
        flush()
        return sections
    }

    /**
     * What's new for someone going from [from] (null = unknown) to [to]: every section newer than [from]
     * and not newer than [to] — so skipping versions shows all of them. A dev/pre-release build of [to]
     * (e.g. 0.2.0-dev.7) counts as [to].
     */
    fun between(sections: List<Section>, from: String?, to: String): List<Section> {
        val target = core(to)
        val start = from?.let(::core)
        return sections.filter { s ->
            val v = core(s.version)
            compare(v, target) <= 0 && (start == null && compare(v, target) == 0 || start != null && compare(v, start) > 0)
        }
    }

    private fun core(version: String): List<Int> =
        version.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }

    internal fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
