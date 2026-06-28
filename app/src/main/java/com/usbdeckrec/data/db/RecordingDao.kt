package com.usbdeckrec.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY dateCreated DESC")
    fun getAllRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): RecordingEntity?

    @Insert
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Delete
    suspend fun deleteRecording(recording: RecordingEntity)

    /**
     * Transactional delete of a recording, ensuring the database operation is
     * atomic. Used by RecordingRepository.deleteRecording so that if the app
     * crashes between DB deletion and file deletion, no orphaned database
     * entries are left behind.
     */
    @Transaction
    suspend fun deleteRecordingTransactional(recording: RecordingEntity) {
        deleteRecording(recording)
    }

    @Query("SELECT filePath FROM recordings")
    suspend fun getAllFilePaths(): List<String>

    @Query("SELECT * FROM track_markers WHERE recordingId = :recordingId ORDER BY positionMs ASC")
    fun getTrackMarkers(recordingId: Long): Flow<List<TrackMarkerEntity>>

    @Insert
    suspend fun insertTrackMarker(marker: TrackMarkerEntity): Long
}
