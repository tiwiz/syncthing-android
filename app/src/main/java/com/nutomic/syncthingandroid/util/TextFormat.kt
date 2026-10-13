@file:JvmName("TextFormat")

package com.nutomic.syncthingandroid.util

import android.content.Context
import android.os.Build
import com.nutomic.syncthingandroid.R
import java.io.File
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

// Formatting of sizes, rates, paths and times for the UI.

private const val UNIT_BASE = 1024.0
private const val BITS_PER_BYTE = 8
private const val MAX_CHARS_SUBDIR = 15
private const val MAX_CHARS_FILENAME = MAX_CHARS_SUBDIR * 2
private const val ELLIPSIS = "\u22ef"

/** Converts a number of bytes to a human readable file size (eg 3.5 GiB). */
fun readableFileSize(context: Context, bytes: Double): String =
    readable(context.resources.getStringArray(R.array.file_size_units), bytes)

/** Converts a number of bits per second to a human readable transfer rate (eg 100 KiB/s). */
fun readableTransferRate(context: Context, bits: Long): String =
    readable(
        context.resources.getStringArray(R.array.transfer_rate_units),
        (bits / BITS_PER_BYTE).toDouble(),
    )

/**
 * [amount] in the largest unit that keeps it at 1 or more; sizes past the largest unit stay in it
 * (e.g. "1,024 TB"). Based on http://stackoverflow.com/a/5599842
 */
private fun readable(units: Array<String>, amount: Double): String {
    if (amount <= 0) return "0 " + units[0]
    val digitGroups = min((log10(amount) / log10(UNIT_BASE)).toInt(), units.size - 1)
    return DecimalFormat("#,##0.#").format(amount / UNIT_BASE.pow(digitGroups)) +
        " " +
        units[digitGroups]
}

/** Normalizes [path] ("a/./b/../c" → "a/c"). */
fun formatPath(path: String): String = File(path).toURI().normalize().path

/** Shortens a path using ellipsis to display it on UI where we have little space to display it. */
fun getPathEllipsis(fullFN: String): String {
    val parts = fullFN.split('/')
    val dirs =
        parts.dropLast(1).map {
            if (it.length > MAX_CHARS_SUBDIR) it.take(MAX_CHARS_SUBDIR) + ELLIPSIS else it
        }
    return (dirs + shortenFileName(parts.last())).joinToString("/")
}

private fun shortenFileName(name: String): String {
    if (name.length <= MAX_CHARS_FILENAME) {
        return name
    }
    val indexFileExt = name.lastIndexOf('.')
    if (indexFileExt <= 0) {
        // Filename without extension.
        return name.take(MAX_CHARS_FILENAME) + ELLIPSIS
    }
    // Filename with extension.
    val fileName = name.substring(0, indexFileExt)
    val shortened =
        if (fileName.length > MAX_CHARS_FILENAME) fileName.take(MAX_CHARS_FILENAME) + ELLIPSIS
        else fileName
    return shortened + name.substring(indexFileExt)
}

/** Shortens a few sample paths; a manual check, the results are not used. */
fun testPathEllipsis() {
    listOf(
            "",
            "/",
            "//",
            "go2sync.dll",
            "MY-LINK-/Cool 12345 Configuration Utility/sdk/bin/go2sync.dll",
            "MY-LINK-Iam-bin-sum-another-and-make-long/Cool 12345 Configuration Utility/sdk/bin/go2sync.dll",
            "MY-LINK-Iam-bin-sum-another-and-make-long-textfiles-are-cool.txt",
            "MY-LINK-/Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long/" +
                "go2sync-long-textfiles-are-cool.dll",
            "MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long/" +
                "go2sync-long-textfiles-are-cool.dll",
            "MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//" +
                "go2sync-long-textfiles-are-cool.dll",
            "MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//" +
                "go2sync-long-textfiles-are-cool",
            "MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//" +
                "go2sync-long-textfiles-are-cool.correctlongeextensionswassolldas-denn-bitte",
            "MY-LINK-Iam-bin-sum-another-and-make-long/Cool 12345 Configuration Utility/sdk/bin/" +
                "go2sync.dllcorrectlongeextensionswassolldas-denn-bitte",
        )
        .forEach(::getPathEllipsis)
}

fun containsIgnoreCase(src: String, what: String): Boolean = src.contains(what, ignoreCase = true)

/** Converts [dateTime] to a readable localized date and time in the device's zone. */
fun formatDateTime(dateTime: String): String = formatLocal(dateTime, withDate = true)

/** Converts [dateTime] to a readable localized time in the device's zone. */
fun formatTime(dateTime: String): String = formatLocal(dateTime, withDate = false)

private fun formatLocal(dateTime: String, withDate: Boolean): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return dateTime
    }
    val formatter =
        if (withDate) DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        else DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
    val zonedDateTime = ZonedDateTime.parse(dateTime).withZoneSameInstant(ZoneId.systemDefault())
    return formatter.withLocale(Locale.getDefault()).format(zonedDateTime)
}

/** The local time as an ISO offset timestamp in UTC, e.g. "2021-02-11T22:11:29.356Z". */
fun getLocalZonedDateTime(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return "2021-02-11T22:11:29.356Z"
    }
    return ZonedDateTime.ofLocal(LocalDateTime.now(), ZoneId.of("UTC"), ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
