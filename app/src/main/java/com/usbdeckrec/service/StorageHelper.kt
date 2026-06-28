package com.usbdeckrec.service

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provides file I/O helpers for saving recording output files.
 *
 * ## Storage location
 *
 * Recordings are saved to `Music/USB DeckRec/` in the device's public Music folder:
 * - **Android 10+ (API 29+)**: Uses [MediaStore] to write to the Music collection.
 *   Files are visible in any file manager under `Music/USB DeckRec/`.
 * - **Android 9 and below (API < 29)**: Uses
 *   [Environment.getExternalStoragePublicDirectory] directly.
 *   Files are visible under `Music/USB DeckRec/`.
 *
 * No runtime storage permissions are required on Android 10+. On older versions
 * the `WRITE_EXTERNAL_STORAGE` permission (already declared in the manifest) is
 * needed.
 */
object StorageHelper {

    private const val SUBDIR = "USB DeckRec"

    /**
     * Holds the content URI of the most recently created file via MediaStore.
     * Set after [createAudioFile] completes on API 29+. Used for playback
     * and sharing under scoped storage.
     */
    @Volatile
    var lastCreatedFileUri: Uri? = null
        private set

    /**
     * Holds the absolute file path of the most recently created file.
     * Set after [createAudioFile] completes. The path is always the **final**
     * expected on-disk location (never a `.pending-*` temporary name).
     */
    @Volatile
    var lastCreatedFilePath: String = ""
        private set

    /**
     * Create a new audio output file and return its [OutputStream].
     *
     * ## API level behaviour
     *
     * - **API 29+** – Inserts a new entry into [MediaStore.Audio.Media]
     *   under `Music/USB DeckRec/`. Returns a content-provider [OutputStream],
     *   the absolute path on disk, and stores the [Content URI][lastCreatedFileUri].
     * - **API < 29** – Creates a file in
     *   `Environment.getExternalStoragePublicDirectory(Music)/USB DeckRec/`.
     *   Returns a [FileOutputStream] and the absolute path.
     *
     * @param context  Application context
     * @param extension  File extension (e.g., "flac", "wav")
     * @return  Pair of (OutputStream, absolute file path)
     * @throws IOException if the file cannot be created
     */
    @Throws(IOException::class)
    fun createAudioFile(context: Context, extension: String = "flac"): Pair<OutputStream, String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createViaMediaStore(context, extension)
        } else {
            createViaLegacyFile(context, extension)
        }
    }

    /**
     * Create an audio file via [MediaStore] (Android 10+).
     *
     * The file is written to `Music/USB DeckRec/` in the public Music collection.
     * Returns a content-provider-backed [OutputStream] and the real file path.
     *
     * **Important:** On Android 11+ the file starts with `IS_PENDING=1`, which
     * causes MediaStore to use a temporary `.pending-*` name internally. The
     * [lastCreatedFilePath] is set to the **final** expected path, NOT the
     * temporary name — so callers always get the correct, stable path.
     */
    private fun createViaMediaStore(context: Context, extension: String): Pair<OutputStream, String> {
        val mimeType = when (extension.lowercase(Locale.US)) {
            "flac" -> "audio/flac"
            "wav"  -> "audio/wav"
            else   -> "audio/$extension"
        }
        val fileName = buildFileName(extension)

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$SUBDIR")
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.TITLE, fileName.removeSuffix(".$extension"))
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            // On Android 11+ mark as pending so the media scanner won't pick
            // up a partially-written file. Cleared when the encoder closes.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val uri = context.contentResolver.insert(collectionUri, contentValues)
            ?: throw IOException("Failed to create MediaStore entry for $fileName")

        lastCreatedFileUri = uri

        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Failed to open output stream for $uri")

        // Do NOT query _DATA while IS_PENDING=1 on API 30+ — it returns a
        // temporary ".pending-*" name. Instead, construct the final path directly.
        val expectedPath = constructExpectedPath(context, fileName)
        lastCreatedFilePath = expectedPath

        return Pair(stream, expectedPath)
    }

    /**
     * Construct the final on-disk file path for a given file name.
     *
     * This is the path the file will have once `IS_PENDING` is cleared,
     * and is the same path that file managers and other apps will see.
     */
    private fun constructExpectedPath(context: Context, fileName: String): String {
        // Use Environment.getExternalStorageDirectory() for a device-appropriate path.
        // On most devices this is /storage/emulated/0, but adoptable storage or OEM
        // customizations may use different mount points. Falling back to the ContentResolver
        // query on API 30+ if available.
        val musicBase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Try to resolve the actual path via a ContentResolver query first,
            // but only after the IS_PENDING flag has been cleared (i.e., after
            // finalizeMediaStoreFileIfPending is called). During construction we
            // use the standard path.
            val externalStorage = Environment.getExternalStorageDirectory()
            "${externalStorage.absolutePath}/Music"
        } else {
            "${Environment.getExternalStorageDirectory()}/Music"
        }
        return "$musicBase/$SUBDIR/$fileName"
    }

    /**
     * Query the actual on-disk path of a MediaStore file after it has been finalized.
     * This is the authoritative path on API 30+ and handles adoptable storage correctly.
     */
    fun queryActualPath(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // On older APIs, fall back to the constructed path.
            return lastCreatedFilePath.ifEmpty { null }
        }
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (columnIndex >= 0) {
                    cursor.getString(columnIndex)
                } else {
                    lastCreatedFilePath.ifEmpty { null }
                }
            } else {
                lastCreatedFilePath.ifEmpty { null }
            }
        } catch (e: Exception) {
            android.util.Log.e("StorageHelper",
                "Failed to query actual path for $uri: ${e.message}")
            lastCreatedFilePath.ifEmpty { null }
        } finally {
            cursor?.close()
        }
    }

    /**
     * Delete a MediaStore file that was previously created but should not be kept
     * (e.g., because the recording failed due to disk full).
     * Also clears [lastCreatedFileUri] and [lastCreatedFilePath].
     */
    fun deleteMediaStoreFileIfPending(context: Context) {
        val uri = lastCreatedFileUri ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // On API 30+, just delete the pending entry. The file
                // still has IS_PENDING=1 and the media scanner won't pick it up.
                context.contentResolver.delete(uri, null, null)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
            android.util.Log.d("StorageHelper",
                "Deleted incomplete MediaStore entry: $uri")
        } catch (e: Exception) {
            android.util.Log.e("StorageHelper",
                "Failed to delete MediaStore entry $uri: ${e.message}", e)
        } finally {
            lastCreatedFileUri = null
            lastCreatedFilePath = ""
        }
    }

    /**
     * Mark a MediaStore file as no longer pending (API 30+).
     *
     * Must be called by [RecordingService] after the encoder and output stream
     * are fully closed, so the system renames the file from the temporary
     * `.pending-*` name to the final name and indexes it.
     */
    fun finalizeMediaStoreFileIfPending(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val uri = lastCreatedFileUri ?: return

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    /**
     * Create an audio file using the legacy file API (Android 9 and below).
     *
     * The file is created in
     * `Environment.getExternalStoragePublicDirectory(Music)/USB DeckRec/`.
     */
    private fun createViaLegacyFile(context: Context, extension: String): Pair<FileOutputStream, String> {
        val dir = getRecordingDirectory(context)
        val fileName = buildFileName(extension)
        val file = File(dir, fileName)
        val path = file.absolutePath

        lastCreatedFilePath = path
        lastCreatedFileUri = null // No MediaStore URI for legacy path

        return Pair(FileOutputStream(file), path)
    }

    /**
     * Get or create the root recording directory.
     *
     * **Note:** On Android 10+ the public directory returned by this method may
     * not be directly accessible via `File` APIs due to scoped storage.
     * This method is primarily intended for:
     * - Android 9 and below (where direct file access works)
     * - Display purposes (showing the path to the user)
     *
     * For actual file I/O on API 29+, use [createAudioFile] which goes through
     * [MediaStore].
     */
    fun getRecordingDirectory(context: Context): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MUSIC
        )
        val dir = File(musicDir, SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Build a timestamped file name.
     */
    private fun buildFileName(extension: String): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        return "${timestamp}_recording.$extension"
    }
}
