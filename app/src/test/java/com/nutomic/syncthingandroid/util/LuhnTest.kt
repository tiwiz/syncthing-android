package com.nutomic.syncthingandroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vectors from Syncthing's `lib/protocol/luhn_test.go` and `deviceid_test.go`. */
class LuhnTest {

    private val luhn = Luhn()

    @Test
    fun `generates the check character of the upstream test vector`() {
        assertEquals("G", luhn.generate("AB725E4GHIQPL3ZFGT".toByteArray()))
    }

    @Test
    fun `returns null for characters outside the base32 alphabet`() {
        assertNull(luhn.generate("3734EJEKMRHWPZQTWYQ1".toByteArray()))
    }

    @Test
    fun `check characters of every chunk of a real device id`() {
        val id = "P56IOI7MZJNU2YIQGDREYDM2MGTIMGL3BXNPQ6W5BMTBBZ4TJXZWICQ2"
        for (chunk in 0 until 4) {
            val body = id.substring(chunk * 14, chunk * 14 + 13)
            val check = id.substring(chunk * 14 + 13, chunk * 14 + 14)
            assertEquals("chunk $chunk", check, luhn.generate(body.toByteArray()))
        }
    }

    @Test
    fun `empty input gives the first character of the alphabet`() {
        assertEquals("A", luhn.generate(ByteArray(0)))
    }
}
