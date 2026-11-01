package app.oneassist.ui.importer

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Largest CSV we accept. A year of bank history is far below this. */
const val MAX_CSV_BYTES = 5 * 1024 * 1024

class FileTooLargeException : IOException("This file is larger than 5 MB.")

/**
 * Reads a shared or picked document as UTF-8 text, refusing anything over [maxBytes].
 * Only content:// URIs are accepted, so another app cannot point us at our own private files.
 * Call from a background dispatcher.
 */
fun ContentResolver.readTextLimited(uri: Uri, maxBytes: Int = MAX_CSV_BYTES): String {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException("Unsupported file location.")
    val input = openInputStream(uri) ?: throw IOException("Could not open the file.")
    val bytes = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw FileTooLargeException()
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
    return String(bytes, Charsets.UTF_8).removePrefix("﻿")
}
