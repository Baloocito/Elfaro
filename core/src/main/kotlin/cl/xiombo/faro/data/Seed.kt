package cl.xiombo.faro.data

import kotlin.random.Random

/**
 * Semilla compartible (§F4): 8 letras de un alfabeto sin letras confusas (sin I, O, 0 ni 1).
 * 32 símbolos × 8 = 40 bits. La misma semilla da los mismos pisos.
 */
object Seed {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LEN = 8
    private const val MASK = (1L shl 40) - 1

    fun random(): Long = Random.nextLong() and MASK

    fun encode(seed: Long): String {
        var v = seed and MASK
        val sb = StringBuilder()
        repeat(LEN) {
            sb.append(ALPHABET[(v and 31L).toInt()])
            v = v ushr 5
        }
        return sb.reverse().toString()
    }

    fun decode(code: String): Long? {
        if (code.length != LEN) return null
        var v = 0L
        for (ch in code.uppercase()) {
            val i = ALPHABET.indexOf(ch)
            if (i < 0) return null
            v = (v shl 5) or i.toLong()
        }
        return v
    }

    /** Semilla de un piso: depende solo de la semilla de la run y del número de piso. */
    fun floor(seed: Long, floor: Int): Long {
        var z = seed * 0x9E3779B97F4A7C15uL.toLong() + floor * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
}
