package com.nutomic.syncthingandroid.util

/**
 * Java's `String.split` for a literal delimiter, for code converted from Java: the string itself
 * when the delimiter is not found, otherwise the parts without the trailing empty ones (Kotlin's
 * `split` keeps them).
 */
fun String.splitLikeJava(delimiter: String): List<String> =
    if (!contains(delimiter)) listOf(this) else split(delimiter).dropLastWhile { it.isEmpty() }
