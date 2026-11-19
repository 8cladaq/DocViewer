package com.example.doclens.engine

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A single mutation, executed against an open PDFBox 2.0 document on Dispatchers.IO. */
data class EditCommand(
    val label: String,
    private val action: (PDDocument) -> Unit,
) {
    fun execute(document: PDDocument) = action(document)
}

data class DocumentState(
    val bytes: ByteArray,
    val pageCount: Int,
    val sourceName: String? = null,
)

data class HistoryInfo(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val undoLabel: String?,
    val redoLabel: String?,
)

data class HistoryResult(
    val applied: Boolean,
    val label: String?,
    val state: DocumentState,
)

private data class HistoryEntry(
    val command: EditCommand,
    val before: DocumentState,
    val after: DocumentState,
)

/**
 * Thread-safe owner of the current PDF bytes. All PDFBox work is performed on
 * [Dispatchers.IO] with `PDDocument.load(...)` + `use {}`, temp-file-backed memory,
 * and temp-file atomic replacement on save.
 */
class PdfDocumentRepository private constructor(context: Context) {

    private val mutex = Mutex()
    private var state: DocumentState? = null
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    init {
        val applicationContext = context.applicationContext ?: context
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(applicationContext)
        }
    }

    /** Loads a PDF and clears edit history. */
    suspend fun load(bytes: ByteArray, sourceName: String? = null): DocumentState =
        withContext(Dispatchers.IO) {
            require(bytes.isNotEmpty()) { "PDF bytes must not be empty" }
            val pageCount = readPageCount(bytes)
            val loaded = DocumentState(bytes.copyOf(), pageCount, sourceName)
            mutex.withLock {
                undoStack.clear()
                redoStack.clear()
                state = loaded
                loaded.snapshot()
            }
        }

    /** Returns a defensive copy of the current document state. */
    suspend fun currentState(): DocumentState? = withContext(Dispatchers.IO) {
        mutex.withLock { state?.snapshot() }
    }

    /**
     * Opens the current bytes for read-only PDFBox work. The document is closed
     * before the result is returned, so [block] must not retain PDFBox objects.
     */
    suspend fun <T> read(block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = requireStateLocked().bytes
            ByteArrayInputStream(bytes).use { input ->
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                    block(document)
                }
            }
        }
    }

    /** Executes [command], pushes one undo entry, and clears the redo stack. */
    suspend fun execute(command: EditCommand): DocumentState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = requireStateLocked()
            val resultBytes = ByteArrayInputStream(before.bytes).use { input ->
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                    command.execute(document)
                    ByteArrayOutputStream().use { output ->
                        document.save(output)
                        output.toByteArray()
                    }
                }
            }
            val after = DocumentState(resultBytes, readPageCount(resultBytes), before.sourceName)
            undoStack.addLast(HistoryEntry(command, before, after))
            redoStack.clear()
            state = after
            after.snapshot()
        }
    }

    /** Reverts the most recent command, if any. */
    suspend fun undo(): HistoryResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = undoStack.removeLastOrNull()
            if (entry == null) {
                HistoryResult(false, null, requireStateLocked().snapshot())
            } else {
                redoStack.addLast(entry)
                state = entry.before
                HistoryResult(true, entry.command.label, entry.before.snapshot())
            }
        }
    }

    /** Re-applies the most recently undone command, if any. */
    suspend fun redo(): HistoryResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = redoStack.removeLastOrNull()
            if (entry == null) {
                HistoryResult(false, null, requireStateLocked().snapshot())
            } else {
                undoStack.addLast(entry)
                state = entry.after
                HistoryResult(true, entry.command.label, entry.after.snapshot())
            }
        }
    }

    suspend fun history(): HistoryInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            HistoryInfo(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                undoLabel = undoStack.lastOrNull()?.command?.label,
                redoLabel = redoStack.lastOrNull()?.command?.label,
            )
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        mutex.withLock {
            undoStack.clear()
            redoStack.clear()
        }
    }

    /** Writes current bytes to a sibling temp file, then renames it into place. */
    suspend fun saveTo(destination: File): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = requireStateLocked().bytes
            val target = destination.absoluteFile
            val directory = target.parentFile ?: File(".")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Cannot create directory: ${directory.absolutePath}")
            }

            val temp = File(directory, "${target.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                replaceAtomically(temp, target)
                target
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            state = null
            undoStack.clear()
            redoStack.clear()
        }
    }

    private fun requireStateLocked(): DocumentState =
        state ?: throw IllegalStateException("No PDF is loaded")

    private fun readPageCount(bytes: ByteArray): Int =
        ByteArrayInputStream(bytes).use { input ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                document.numberOfPages
            }
        }

    private fun replaceAtomically(temp: File, destination: File) {
        if (temp.renameTo(destination)) return

        val backup = File(destination.parentFile, "${destination.name}.bak")
        if (backup.exists() && !backup.delete()) {
            throw IOException("Cannot remove stale backup: ${backup.absolutePath}")
        }
        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("Cannot move existing PDF aside: ${destination.absolutePath}")
        }
        if (!temp.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination)
            throw IOException("Cannot install temporary PDF: ${destination.absolutePath}")
        }
        if (backup.exists()) backup.delete()
    }

    private fun DocumentState.snapshot(): DocumentState =
        copy(bytes = bytes.copyOf())

    companion object {
        fun create(context: Context): PdfDocumentRepository = PdfDocumentRepository(context)
    }
}
