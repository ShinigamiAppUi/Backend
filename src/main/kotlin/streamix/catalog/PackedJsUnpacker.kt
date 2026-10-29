package streamix.catalog

/**
 * Small JVM-only decoder for Dean Edwards P.A.C.K.E.R.-style scripts.
 *
 * This is an original implementation of the decoding algorithm needed by
 * catalog resolvers; it does not execute JavaScript and has no Android or
 * provider dependency.
 */
internal object PackedJsUnpacker {
    private val packedPattern = Regex(
        """eval\(function\(p,a,c,k,e,[rd]?""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val packedBlockPattern = Regex(
        """\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val tokenPattern = Regex("""\b\w+\b""")

    fun unpackAndCombine(script: String): String? {
        if (!packedPattern.containsMatchIn(script)) return null

        val unpacked = packedBlockPattern.findAll(script).mapNotNull { match ->
            val payload = match.groups[1]?.value ?: return@mapNotNull null
            val radix = match.groups[2]?.value?.toIntOrNull() ?: return@mapNotNull null
            val count = match.groups[3]?.value?.toIntOrNull() ?: return@mapNotNull null
            val dictionary = match.groups[4]?.value?.split('|') ?: return@mapNotNull null

            if (dictionary.size != count || radix < 2) return@mapNotNull null

            tokenPattern.replace(payload) { token ->
                val index = unbase(token.value, radix)
                dictionary.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: token.value
            }
        }.toList()

        return unpacked.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun unbase(value: String, radix: Int): Int {
        if (radix in 2..36) return value.toIntOrNull(radix) ?: -1

        val alphabet = when {
            radix <= 52 -> "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP".take(radix)
            radix <= 54 -> "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR".take(radix)
            radix <= 62 -> "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".take(radix)
            else -> return -1
        }

        var result = 0L
        value.reversed().forEachIndexed { position, character ->
            val digit = alphabet.indexOf(character)
            if (digit < 0) return -1
            result += digit.toLong() * Math.pow(radix.toDouble(), position.toDouble()).toLong()
        }
        return result.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: -1
    }
}
