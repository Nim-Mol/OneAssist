package app.oneassist.ingest

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition (ML Kit, bundled model). Nothing leaves the phone. */
object ScreenshotOcr {

    /**
     * Returns the text lines of the image in reading order. Lines whose vertical centres line up
     * (merchant on the left, amount on the right) are joined into one line.
     */
    suspend fun recognizeLines(context: Context, uri: Uri): List<String> {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = recognizer.process(image).await()
            return withContext(Dispatchers.Default) { toRows(result) }
        } finally {
            recognizer.close()
        }
    }

    private data class OcrLine(val text: String, val top: Int, val bottom: Int, val left: Int) {
        val centerY get() = (top + bottom) / 2
        val height get() = (bottom - top).coerceAtLeast(1)
    }

    private fun toRows(text: Text): List<String> {
        val lines = text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox
                OcrLine(line.text, box?.top ?: 0, box?.bottom ?: 0, box?.left ?: 0)
            }
        }.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        if (lines.all { it.top == 0 && it.bottom == 0 }) return lines.map { it.text }

        val sorted = lines.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(line.centerY - row.first().centerY) < minOf(row.first().height, line.height) / 2) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }
}
