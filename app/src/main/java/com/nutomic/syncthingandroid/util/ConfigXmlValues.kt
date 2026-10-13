package com.nutomic.syncthingandroid.util

import org.w3c.dom.Element
import org.w3c.dom.Node

// Reading config.xml values, for ConfigXml. The parsing rules are the Java original's: a missing
// attribute or element, or a number that does not parse, gives the default; booleans follow
// Boolean.parseBoolean ("true" in any case, anything else is false).

internal fun Element.attrOr(name: String, default: Boolean): Boolean =
    if (hasAttribute(name)) java.lang.Boolean.parseBoolean(getAttribute(name)) else default

internal fun Element.attrOr(name: String, default: Int): Int =
    if (!hasAttribute(name)) default else parseIntOr(getAttribute(name), default)

internal fun Element.attrOr(name: String, default: Float): Float =
    if (!hasAttribute(name)) default else parseFloatOr(getAttribute(name), default)

internal fun Element.attrOr(name: String, default: String): String =
    if (hasAttribute(name)) getAttribute(name) else default

internal fun Node?.contentOr(default: Boolean): Boolean =
    if (this == null) default else java.lang.Boolean.parseBoolean(textContent)

internal fun Node?.contentOr(default: Int): Int =
    if (this == null) default else parseIntOr(textContent, default)

internal fun Node?.contentOr(default: Float): Float =
    if (this == null) default else parseFloatOr(textContent, default)

internal fun Node?.contentOr(default: String): String = if (this == null) default else textContent

internal fun Node?.contentOrNullable(default: String?): String? =
    if (this == null) default else textContent

private fun parseIntOr(text: String?, default: Int): Int =
    try {
        Integer.parseInt(text)
    } catch (_: NumberFormatException) {
        default
    }

private fun parseFloatOr(text: String?, default: Float): Float =
    try {
        java.lang.Float.parseFloat(checkNotNull(text))
    } catch (_: NumberFormatException) {
        default
    }
