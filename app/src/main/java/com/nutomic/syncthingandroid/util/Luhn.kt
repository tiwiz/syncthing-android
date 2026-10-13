package com.nutomic.syncthingandroid.util

class Luhn {
    /**
     * A check digit for [s], which should be composed of characters from [LUHN_ALPHABET]; null when
     * it is not. Doesn't follow the actual Luhn algorithm, see
     * https://forum.syncthing.net/t/v0-9-0-new-node-id-format/478/6 for more.
     */
    fun generate(s: ByteArray): String? {
        val n = LUHN_ALPHABET.length
        var factor = 1
        var sum = 0
        for (b in s) {
            val codepoint = LUHN_ALPHABET.indexOf(b.toInt().toChar())
            if (codepoint == -1) {
                // Error "Digit %q not valid in alphabet %q", s[i], a
                return null
            }
            var addend = factor * codepoint
            factor = if (factor == 2) 1 else 2
            addend = addend / n + addend % n
            sum += addend
        }
        val checkCodepoint = (n - sum % n) % n
        return LUHN_ALPHABET.substring(checkCodepoint, checkCodepoint + 1)
    }

    private companion object {
        /** An alphabet is a string of N characters, representing the digits of a given base N. */
        const val LUHN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
