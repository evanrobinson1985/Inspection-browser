package com.inspectionbrowser.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copying text safely, and saving text as .txt files in Downloads for when it's too big to copy. */
object TextFiles {
    /** Characters; Android's clipboard fails (or crashes the app) on much more than ~1 MB. */
    const val CLIPBOARD_LIMIT = 400_000

    private val main = Handler(Looper.getMainLooper())
    private fun toast(context: Context, text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    /** "console-fcs.caves.org-2026-11-12-101500.txt" */
    fun fileName(kind: String, pageUrl: String): String {
        val host = Uri.parse(pageUrl).host?.replace(Regex("[^A-Za-z0-9.-]"), "")?.ifEmpty { null } ?: "page"
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        return "$kind-$host-$stamp.txt"
    }

    /**
     * Copies [text]; if it's too big for the clipboard, copies its first part and says to save
     * it as a .txt file instead.
     */
    fun copy(context: Context, text: String, label: String) {
        val tooBig = text.length > CLIPBOARD_LIMIT
        runCatching {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(label, if (tooBig) text.take(CLIPBOARD_LIMIT) else text))
        }.onSuccess {
            toast(context, if (tooBig) "$label is too big for the clipboard: copied the first part. Use Save as .txt for all of it" else "$label copied")
        }.onFailure {
            toast(context, "Couldn't copy $label (too big for the clipboard); use Save as .txt")
        }
    }

    /** Saves [text] as a .txt file in Downloads (in the background) and says where it went. */
    fun save(context: Context, name: String, text: String) {
        val app = context.applicationContext
        Thread {
            runCatching { write(app, name, text) }
                .onSuccess { toast(app, "Saved to $it") }
                .onFailure { toast(app, "Couldn't save the file: ${it.message}") }
        }.start()
    }

    private fun write(context: Context, name: String, text: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads isn't available")
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("couldn't write to Downloads")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Downloads/$name"
        }
        // Android 8 and 9: the app's own Downloads folder, which needs no storage permission.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("storage isn't available")
        val file = File(dir.apply { mkdirs() }, name)
        file.writeText(text)
        return file.absolutePath
    }
}
