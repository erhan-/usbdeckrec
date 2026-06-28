package com.usbdeckrec.data.repository

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.usbdeckrec.data.db.AppDatabase
import com.usbdeckrec.data.db.RecordingEntity
import com.usbdeckrec.data.db.TrackMarkerEntity
import com.usbdeckrec.model.RecordedTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Repository that wraps [RecordingDao] and [AppDatabase] to expose domain-level
 * [RecordedTrack] models to ViewModels and use cases.
 *
 * All insert/delete operations are suspend functions that run on the caller's
 * coroutine context. Queries return reactive [Flow]s that emit whenever the
 * underlying Room table changes.
 */
class RecordingRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.recordingDao()
    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "DeckRec_RecordingRepo"
        private const val RECORDINGS_SUBDIR = "USB DeckRec"
        private val SUPPORTED_EXTENSIONS = setOf("flac", "wav", "mp3", "ogg", "aac", "m4a")
    }

    /**
     * Observe all recordings ordered by creation date (newest first).
     * Maps Room entities to domain models automatically.
     */
    fun getAllRecordings(): Flow<List<RecordedTrack>> {
        return dao.getAllRecordings().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /**
     * Get a single recording by its database ID.
     * Returns null if no recording with that ID exists.
     */
    suspend fun getRecordingById(id: Long): RecordedTrack? {
        return dao.getRecordingById(id)?.toDomainModel()
    }

    /**
     * Insert a new recording into the database.
     * Maps the domain model to a Room entity and persists it.
     *
     * @return The auto-generated row ID of the inserted recording.
     */
    suspend fun insertRecording(recording: RecordedTrack): Long {
        return dao.insertRecording(recording.toEntity())
    }

    /**
     * Delete a recording from both the database and file system.
     *
     * The database record is deleted first (inside a Room transaction) so that
     * if the app crashes during file deletion, only an orphaned file remains
     * (which is recoverable via [syncRecordingsFromFilesystem]) rather than
     * an orphaned database entry referencing a non-existent file.
     *
     * File deletion is best-effort; if the file cannot be removed the
     * database record is still removed.
     */
    suspend fun deleteRecording(recording: RecordedTrack) {
        // Delete the database record first (within Room transaction)
        dao.deleteRecordingTransactional(recording.toEntity())

        // Delete the file from disk (best-effort)
        try {
            val file = File(recording.filePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: SecurityException) {
            // Best-effort file deletion
        }
    }

    /**
     * Insert a track marker associated with a recording.
     */
    suspend fun insertTrackMarker(marker: TrackMarkerEntity): Long {
        return dao.insertTrackMarker(marker)
    }

    /**
     * Observe all track markers for a given recording, ordered by position.
     */
    fun getTrackMarkers(recordingId: Long): Flow<List<TrackMarkerEntity>> {
        return dao.getTrackMarkers(recordingId)
    }

    /**
     * Scan the public Music/USB DeckRec/ directory for audio files that
     * are NOT yet tracked in the database and re-import them as recordings.
     *
     * This handles the case where the app was reinstalled and the internal
     * Room database was wiped, but the recording files themselves survive
     * in the public Music folder (external storage).
     *
     * **Scoped storage:** On Android 10+ (API 29+) we query [MediaStore]
     * because the direct [File] API cannot access the public Music folder.
     * On older versions we fall back to the traditional file-system scan.
     */
    suspend fun syncRecordingsFromFilesystem() {
        val trackedPaths = dao.getAllFilePaths().toHashSet()
        var importedCount = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            importedCount = syncViaMediaStore(trackedPaths)
        } else {
            importedCount = syncViaLegacyFileApi(trackedPaths)
        }

        if (importedCount > 0) {
            Log.i(TAG, "syncRecordingsFromFilesystem: re-imported $importedCount orphaned recording(s)")
        }
    }

    /**
     * Query [MediaStore.Audio.Media] for files in `Music/USB DeckRec/`
     * that are not yet tracked in the database (API 29+).
     */
    private suspend fun syncViaMediaStore(trackedPaths: Set<String>): Int {
        var importedCount = 0
        try {
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.MIME_TYPE
            )

            // Filter to only our subdirectory.
            // NOTE: RELATIVE_PATH was added in API 30 (Android 11).
            // On API 29 (Android 10) we fall back to filtering by the DATA column only.
            val selection: String
            val selectionArgs: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ? OR " +
                        "${MediaStore.Audio.Media.DATA} LIKE ?"
                selectionArgs = arrayOf(
                    "Music/$RECORDINGS_SUBDIR/",
                    "%/Music/$RECORDINGS_SUBDIR/%"
                )
            } else {
                // API 29: RELATIVE_PATH not available, use DATA only
                selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
                selectionArgs = arrayOf("%/Music/$RECORDINGS_SUBDIR/%")
            }

            val cursor = appContext.contentResolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use { c ->
                val colName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val colPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val colSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val colDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val colMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                while (c.moveToNext()) {
                    val absPath = c.getString(colPath) ?: continue
                    if (absPath in trackedPaths) continue

                    val fileName = c.getString(colName) ?: continue
                    val extension = fileName.substringAfterLast('.', "").lowercase()
                    if (extension !in SUPPORTED_EXTENSIONS) continue

                    val dateCreated = c.getLong(colDate) * 1000L // seconds → millis
                    val fileSizeBytes = c.getLong(colSize)

                    val recording = RecordingEntity(
                        id = 0,
                        fileName = fileName,
                        filePath = absPath,
                        contentUri = null,
                        fileSizeBytes = fileSizeBytes,
                        durationMs = 0L,
                        sampleRate = 0,
                        bitDepth = 0,
                        channelCount = 2,
                        format = extension.uppercase(),
                        dateCreated = dateCreated,
                        isSession = true,
                        mixerModel = null,
                        mixerProfileJson = null,
                        tags = null
                    )

                    dao.insertRecording(recording)
                    importedCount++
                    Log.d(TAG, "syncViaMediaStore: re-imported $fileName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncViaMediaStore: failed — ${e.message}", e)
        }
        return importedCount
    }

    /**
     * Legacy scan via [File] API for Android 9 and below.
     */
    private suspend fun syncViaLegacyFileApi(trackedPaths: Set<String>): Int {
        var importedCount = 0
        try {
            val recordingsDir = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC
                ),
                RECORDINGS_SUBDIR
            )

            if (!recordingsDir.exists() || !recordingsDir.isDirectory) {
                Log.d(TAG, "syncViaLegacyFileApi: directory does not exist")
                return 0
            }

            val files = recordingsDir.listFiles() ?: return 0

            for (file in files) {
                if (!file.isFile) continue

                val extension = file.extension.lowercase()
                if (extension !in SUPPORTED_EXTENSIONS) continue

                val absPath = file.absolutePath
                if (absPath in trackedPaths) continue

                val fileName = file.name
                val dateCreated = file.lastModified()
                val fileSizeBytes = file.length()

                val recording = RecordingEntity(
                    id = 0,
                    fileName = fileName,
                    filePath = absPath,
                    contentUri = null,
                    fileSizeBytes = fileSizeBytes,
                    durationMs = 0L,
                    sampleRate = 0,
                    bitDepth = 0,
                    channelCount = 2,
                    format = extension.uppercase(),
                    dateCreated = dateCreated,
                    isSession = true,
                    mixerModel = null,
                    mixerProfileJson = null,
                    tags = null
                )

                dao.insertRecording(recording)
                importedCount++
                Log.d(TAG, "syncViaLegacyFileApi: re-imported $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncViaLegacyFileApi: failed — ${e.message}", e)
        }
        return importedCount
    }

    // ── Mapping helpers ──────────────────────────────────────────────

    private fun RecordedTrack.toEntity(): RecordingEntity = RecordingEntity(
        id = id,
        fileName = fileName,
        filePath = filePath,
        contentUri = contentUri,
        fileSizeBytes = fileSizeBytes,
        durationMs = durationMs,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        channelCount = 2,     // always stereo for now
        format = format,
        dateCreated = dateCreated,
        isSession = true,
        mixerModel = null,
        mixerProfileJson = null,
        tags = null
    )

    private fun RecordingEntity.toDomainModel(): RecordedTrack = RecordedTrack(
        id = id,
        fileName = fileName,
        filePath = filePath,
        contentUri = contentUri,
        durationMs = durationMs,
        fileSizeBytes = fileSizeBytes,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        format = format,
        dateCreated = dateCreated
    )
}
