package com.nutomic.syncthingandroid.util

/**
 * Java's `String.split` for a literal delimiter, for code converted from Java: the string itself
 * when the delimiter is not found, otherwise the parts without the trailing empty ones (Kotlin's
 * `split` keeps them).
 */
fun String.splitLikeJava(delimiter: String): List<String> =
    if (!contains(delimiter)) listOf(this) else split(delimiter).dropLastWhile { it.isEmpty() }

/**
 * Java's `String.split(regex)`: the string itself when [regex] does not match, otherwise the parts
 * without the trailing empty ones (a leading empty part stays, as in Java).
 */
fun String.splitLikeJava(regex: Regex): List<String> =
    if (!regex.containsMatchIn(this)) listOf(this) else split(regex).dropLastWhile { it.isEmpty() }
