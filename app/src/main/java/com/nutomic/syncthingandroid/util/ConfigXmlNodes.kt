package com.nutomic.syncthingandroid.util

import org.w3c.dom.Element
import org.w3c.dom.Node

// Finding and changing config.xml elements, for ConfigXml. Reading values is in ConfigXmlValues.kt.

/** The first descendant element called [tagName], or null. */
internal fun Element.firstElement(tagName: String): Element? =
    getElementsByTagName(tagName).item(0) as Element?

/** The direct children called [tagName], not those nested in other elements. */
internal fun Element.childElements(tagName: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length)
        .map(nodes::item)
        .filter { it.nodeName == tagName }
        .map { it as Element }
}

/** All descendant elements called [tagName], in document order. */
internal fun Element.descendantElements(tagName: String): List<Element> {
    val nodes = getElementsByTagName(tagName)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

/**
 * Sets the text of the first descendant [tagName], creating it as a child if needed. Returns
 * whether the text changed. A null [textContent] throws after the element was created, as the Java
 * original did.
 */
internal fun Element.setChildText(tagName: String, textContent: String?): Boolean {
    var element: Node? = getElementsByTagName(tagName).item(0)
    if (element == null) {
        element = ownerDocument.createElement(tagName)
        appendChild(element)
    }
    if (checkNotNull(textContent) != element.textContent) {
        element.textContent = textContent
        return true
    }
    return false
}

internal fun Element.setChildText(tagName: String, value: Boolean?): Boolean =
    setChildText(tagName, checkNotNull(value).toString())

/**
 * Replaces the direct children called [tagName] with one per text. Returns whether any existed or
 * were added.
 */
internal fun Element.setChildTexts(tagName: String, texts: Array<String>): Boolean {
    val existing = descendantElements(tagName).filter { it.parentNode === this }
    existing.forEach(::removeChild)
    for (text in texts) {
        appendChild(ownerDocument.createElement(tagName).apply { textContent = text })
    }
    return existing.isNotEmpty() || texts.isNotEmpty()
}

/**
 * Removes [child] and the indentation before it: Element.removeChild() alone leaves whitespace and
 * a line break. See
 * https://stackoverflow.com/questions/14255064/removechild-how-to-remove-indent-too
 */
internal fun Element.removeIndented(child: Element) {
    val prev = child.previousSibling
    if (
        prev != null && prev.nodeType == Node.TEXT_NODE && prev.nodeValue.trimLikeJava().isEmpty()
    ) {
        removeChild(prev)
    }
    removeChild(child)
}

/** Java's `String.trim()`: strips every character up to the space, not Kotlin's whitespace set. */
internal fun String.trimLikeJava(): String = trim { it <= ' ' }
