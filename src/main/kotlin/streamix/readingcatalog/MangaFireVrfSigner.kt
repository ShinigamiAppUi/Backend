package streamix.readingcatalog

import java.util.Base64

/**
 * MangaFire API VRF signing compatibility implementation.
 *
 * Algorithm and signing tables are adapted from Keiyoushi extensions-source,
 * src/all/mangafire/.../VrfSigner.kt (Apache-2.0). See THIRD_PARTY_NOTICES.md.
 */
internal object MangaFireVrfSigner {
    private data class Stage(val table: ByteArray, val key: ByteArray, val iv: Int)

    private val stages by lazy {
        listOf(
            Stage(Base64.getDecoder().decode(TABLE_1), Base64.getDecoder().decode(KEY_1), 0x5A),
            Stage(Base64.getDecoder().decode(TABLE_2), Base64.getDecoder().decode(KEY_2), 0x35),
            Stage(Base64.getDecoder().decode(TABLE_3), Base64.getDecoder().decode(KEY_3), 0xBA),
        )
    }

    fun sign(path: String): String {
        var data = path.toByteArray(Charsets.UTF_8)
        for (stage in stages) {
            val output = ByteArray(data.size)
            var previous = stage.iv
            for (i in data.indices) {
                previous = stage.table[
                    (data[i].toInt() xor stage.key[i % stage.key.size].toInt() xor previous) and 0xFF
                ].toInt() and 0xFF
                output[i] = previous.toByte()
            }
            data = output
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private const val TABLE_1 = "yINlmUNho8VYJT+ibTIP+9ESiULpVEtMOoD6U6lRE0R/xwXo/Xp9NrUgC4cw/Lmo33vUyjUE40kUoEWIr/fxfNNcq2s79ShQ5NhNrFnJ4hXPwOu/SuXzIbuTQKGFvfm08E9jvCfqAtoDqvQq3dVWPQFmJjgvkISBeXY3BgANR+yVnjGbcxZ47d6kLNfZPIayTq3/YGySb1KuVZodWp/WGNAO5pfMcpaK53Hhs0allBszaMaxuouOwdxbwgxIw6YunSsXjI05Yi0j9j4eHKfSXR8Ifo/Od+8iamRfCXTyvm7NGRGYdcQ0ywcK/u6RXhrbcCm4t2eCtrDgQVecJGkQ+A=="
    private const val KEY_1 = "0Ec58JOY3uBzJK9m3zqIOpdlF7UFiax9DmA="
    private const val TABLE_2 = "IUFltCxD3Oc2cwCgkJffthaOg9cgPUb0LgW6H/VtfcF0kc5F25t+aWj6JH9VOhOaY0rAFdUxlDnl5BLNvwEJvQtP5qcw7vdb/K+chnbwnspSHT8mz5lqwz41TezG0hkO06FTjJZhsyNuFLDpD2ZZxQj/QIRcF90zpmQ7Byu483WsQqUE0C342HL+JXngRB6fRzxRyVTaKu83h7UYTJ0QMt6ixFh6S3F8gqkKwrGTL3jHNBsD45UnifK8+RGtishQV2K3rujLKEkiZxpr2dYcudFW4oFsDKhad3CLBvuyTqsCo4B7mL5IKQ1vXo/MOOvq1I1d8ar9X6Ttu5KF4fZgiA=="
    private const val KEY_2 = "AAdjb1iPY8CiDmq9H34tKTBF8a3oDQ=="
    private const val TABLE_3 = "NQHlu1/wVO5EmkwQymF810qqY2xG1k2obcas4Z9mCsPEIFl9pRIjFxbJ7ybMHbBckT5Ton85E0FOeHezbh/mjlEYpmpnlXOS8dgrqeq2KfxImTh1YK9y0PeMNhzA1OQzSY9brYOJq/l2QnE/hwOeZIhPixVSKIUlDb5vLcH6RWKxkIEMuP0bDwIqQ71AJJaEaMJL7A6YtyIwoRT+L5v4aZzodN/0+3nOGsfblFjgxSfPzVDjNFeNl5P26+kEC/8AHgdrpAbt3hHz3HrRN1Y6e+JHgF7ncFWnoF0y3THL1S71WgWGCa6KtSzTCCG58n68nTyj2T3Sshk7utqCtMi/ZQ=="
    private const val KEY_3 = "DELOJgPsVaCcblDtTGMdHzM="
}
