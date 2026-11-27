package com.jaccollector

import android.util.Base64

/**
 * Addresses that are kept out of plain sight in the APK (the shared list and the rating).
 * This is not protection: anyone who decompiles the app finds them. It only keeps them from
 * showing up in a simple search through the APK. The build scrambles them (scramble() in
 * app/build.gradle.kts), this unscrambles them.
 */
object Hidden {
    fun open(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        for (i in b.indices) b[i] = (b[i].toInt() xor ((i * 31 + 0x5A) and 0xFF)).toByte()
        return String(b, Charsets.UTF_8)
    }
}
