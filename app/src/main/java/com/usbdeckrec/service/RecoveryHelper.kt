package com.usbdeckrec.service

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result of processing a single orphaned pending MediaStore entry.
 *
 * @property uri           The MediaStore [Uri] of the processed entry.
 * @property originalName  The pending display name before recovery.
 * @property recoveredName The new display name after recovery (empty if deleted).
 * @property sizeBytes     Size of the audio data in bytes.
 * @property wasRecovered  `true` if the entry was recovered (IS_PENDING cleared),
 *                         `false` if it was deleted because it was too small.
 */
data class RecoveredFile(
    val uri: Uri,
    val originalName: String,
    val recoveredName: String,
    val sizeBytes: Long,
    val wasRecovered: Boolean
)

/**
 * Scans for and recovers orphaned recordings that were left with [IS_PENDING]=1
 * after an unexpected crash mid-recording.
 *
 * ## How it works
 *
 * On API 30+ the [StorageHelper] sets `IS_PENDING=1` while a recording is in
 * progress. If the app crashes before [StorageHelper.finalizeMediaStoreFileIfPending]
 * runs, the entry remains pending — it's invisible to other apps and the user,
 * and its on-disk name is a temporary `.pending-*` file. This helper finds those
 * entries and either:
 * - Recovers them (clears `IS_PENDING` and assigns a readable name) if they
 *   contain meaningful audio data (> 4 KiB), or
 * - Deletes the entry entirely if it's just a header (too small to be useful).
 *
 * It also handles legacy (API < 29) temporary files left behind after a crash.
 */
object RecoveryHelper {

    private const val TAG = "RecoveryHelper"

    /** Minimum file size (in bytes) to consider a pending file a valid partial
     *  recording rather than an empty header. 4 KiB ≈ 0.1 seconds of FLAC audio. */
    private const val MIN_VALID_SIZE = 4096L

    /** Subdirectory used by [StorageHelper] for all recordings. */
    private const val SUBDIR = "USB DeckRec"

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Query [MediaStore] for all entries with `IS_PENDING=1` inside the app's
     * recording directory, then either recover or delete them.
     *
     * @param context Application context.
     * @return A list of [RecoveredFile] describing what was done for each entry.
     */
    fun scanAndRecoverOrphanedRecordings(context: Context): List<RecoveredFile> {
        val results = mutableListOf<RecoveredFile>()

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Column projection — request _SIZE which may be 0 for pending entries
        // on some devices; we fall back to querying the file directly.
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // Selection: only pending entries in our directory.
        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Audio.Media.IS_PENDING} = 1 AND " +
                    "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("Music/$SUBDIR/%")
        } else {
            selection = "${MediaStore.Audio.Media.IS_PENDING} = 1 AND " +
                    "${MediaStore.Audio.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%/$SUBDIR/%")
        }

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                null
            )
            if (cursor == null || !cursor.moveToFirst()) {
                Log.d(TAG, "No orphaned pending entries found")
                return emptyList()
            }

            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            do {
                val entryId = if (idCol >= 0) cursor.getLong(idCol) else -1L
                val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "unknown" else "unknown"
                val sizeFromCursor = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                val relPath = if (relPathCol >= 0) cursor.getString(relPathCol) ?: "" else ""
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""

                // Build the content URI for this entry.
                val entryUri = Uri.withAppendedPath(uri, entryId.toString())

                // Determine the actual file size. The _SIZE column may be 0
                // while IS_PENDING=1, so try to stat the file directly.
                val actualSize = resolveFileSize(context, entryUri, dataPath, sizeFromCursor)

                val result = processEntry(
                    context = context,
                    entryUri = entryUri,
                    displayName = displayName,
                    fileSize = actualSize,
                    relPath = relPath,
                    dataPath = dataPath
                )
                results.add(result)
            } while (cursor.moveToNext())
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for orphaned recordings: ${e.message}", e)
        } finally {
            cursor?.close()
        }

        Log.d(TAG, "Recovery scan complete: ${results.size} entries processed " +
                "(${results.count { it.wasRecovered }} recovered, " +
                "${results.count { !it.wasRecovered }} deleted as too small)")
        return results
    }

    /**
     * Clean up stale temporary files from the legacy (API < 29) recording
     * directory that may have been left behind after a crash.
     *
     * Safe to call on all API levels; on API 29+ the checks are skipped because
     * the app uses [MediaStore] exclusively.
     */
    fun cleanupOrphanedTempFiles(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On API 29+ the app uses MediaStore, and temp files are managed
            // via IS_PENDING. No legacy `.tmp` files to clean up.
            Log.d(TAG, "Skipping legacy temp cleanup (API >= 29)")
            return
        }

        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MUSIC
        )
        val recordingDir = File(dir, SUBDIR)
        if (!recordingDir.exists()) {
            Log.d(TAG, "Legacy recording directory does not exist: $recordingDir")
            return
        }

        var deletedCount = 0
        val tempFiles = recordingDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".tmp") || file.name.endsWith(".partial"))
        }

        if (tempFiles.isNullOrEmpty()) {
            Log.d(TAG, "No orphaned temp files found in $recordingDir")
            return
        }

        for (file in tempFiles) {
            if (file.delete()) {
                deletedCount++
                Log.d(TAG, "Deleted orphaned temp file: ${file.absolutePath}")
            } else {
                Log.w(TAG, "Failed to delete orphaned temp file: ${file.absolutePath}")
            }
        }

        Log.d(TAG, "Temp cleanup complete: $deletedCount file(s) deleted")
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Process a single pending MediaStore entry.
     *
     * If [fileSize] >= [MIN_VALID_SIZE] the entry is **recovered** – its
     * `IS_PENDING` flag is cleared and its [DISPLAY_NAME] is updated to a
     * human-readable name. Otherwise the entry is **deleted** entirely.
     */
    private fun processEntry(
        context: Context,
        entryUri: Uri,
        displayName: String,
        fileSize: Long,
        relPath: String,
        dataPath: String
    ): RecoveredFile {
        if (fileSize >= MIN_VALID_SIZE) {
            return recoverEntry(context, entryUri, displayName, fileSize)
        } else {
            return deleteEntry(context, entryUri, displayName, fileSize)
        }
    }

    /**
     * Recover a pending entry that contains meaningful audio data.
     *
     * Clears [MediaStore.Audio.Media.IS_PENDING] and assigns a new display name
     * in the format `Recovered_YYYYMMDD_HHmmss.flac`.
     */
    private fun recoverEntry(
        context: Context,
        entryUri: Uri,
        displayName: String,
        fileSize: Long
    ): RecoveredFile {
        val extension = resolveExtension(displayName)
        val recoveredName = buildRecoveredName(extension)

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
            put(MediaStore.Audio.Media.DISPLAY_NAME, recoveredName)
            put(MediaStore.Audio.Media.TITLE, recoveredName.removeSuffix(".$extension"))
        }

        try {
            val updated = context.contentResolver.update(entryUri, values, null, null)
            if (updated > 0) {
                Log.i(TAG, "Recovered pending entry: '$displayName' → '$recoveredName' " +
                        "($fileSize bytes)")
            } else {
                Log.w(TAG, "Update returned 0 rows for $entryUri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover $entryUri: ${e.message}", e)
        }

        return RecoveredFile(
            uri = entryUri,
            originalName = displayName,
            recoveredName = recoveredName,
            sizeBytes = fileSize,
            wasRecovered = true
        )
    }

    /**
     * Delete a pending entry that is too small to be useful (just a header).
     *
     * On API 30+ this also removes the underlying temporary file. On older APIs
     * we also attempt to delete the backing file.
     */
    private fun deleteEntry(
        context: Context,
        entryUri: Uri,
        displayName: String,
        fileSize: Long
    ): RecoveredFile {
        try {
            val deleted = context.contentResolver.delete(entryUri, null, null)
            if (deleted > 0) {
                Log.i(TAG, "Deleted tiny pending entry: '$displayName' ($fileSize bytes)")
            } else {
                Log.w(TAG, "Delete returned 0 rows for $entryUri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete tiny entry $entryUri: ${e.message}", e)
        }

        return RecoveredFile(
            uri = entryUri,
            originalName = displayName,
            recoveredName = "",
            sizeBytes = fileSize,
            wasRecovered = false
        )
    }

    /**
     * Resolve the actual on-disk file size of a pending entry.
     *
     * The [MediaStore.Audio.Media.SIZE] column may be 0 while [IS_PENDING]=1,
     * so we try to stat the backing file via two strategies:
     * 1. Query [_DATA] from the resolver (works on API < 30 after finalization;
     *    may return a `.pending-*` path on API 30+).
     * 2. Use the already-queried [dataPath] if available.
     * 3. Fall back to the size reported by the cursor.
     */
    private fun resolveFileSize(
        context: Context,
        entryUri: Uri,
        dataPath: String,
        cursorSize: Long
    ): Long {
        // Try the data path we already have.
        if (dataPath.isNotBlank()) {
            val file = File(dataPath)
            if (file.exists()) {
                return file.length()
            }
        }

        // If _DATA wasn't in the projection or was null, query it explicitly.
        if (dataPath.isBlank()) {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            var dataCursor: Cursor? = null
            try {
                dataCursor = context.contentResolver.query(entryUri, projection, null, null, null)
                if (dataCursor != null && dataCursor.moveToFirst()) {
                    val col = dataCursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (col >= 0) {
                        val path = dataCursor.getString(col)
                        if (!path.isNullOrBlank()) {
                            val file = File(path)
                            if (file.exists()) {
                                return file.length()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore — fall back to cursor size.
            } finally {
                dataCursor?.close()
            }
        }

        // Last resort: use the size from the cursor (may be 0).
        return cursorSize
    }

    /**
     * Extract the file extension from the pending display name, defaulting
     * to `"flac"` if none can be determined.
     */
    private fun resolveExtension(displayName: String): String {
        val dotIndex = displayName.lastIndexOf('.')
        return if (dotIndex >= 0 && dotIndex < displayName.length - 1) {
            displayName.substring(dotIndex + 1)
        } else {
            "flac"
        }
    }

    /**
     * Build a human-readable recovered-file name in the format
     * `Recovered_YYYYMMDD_HHmmss.ext`.
     */
    private fun buildRecoveredName(extension: String): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        return "Recovered_$timestamp.$extension"
    }
}
