package com.usbdeckrec

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.usbdeckrec.data.db.AppDatabase
import com.usbdeckrec.data.db.RecordingDao
import com.usbdeckrec.data.db.RecordingEntity
import com.usbdeckrec.data.db.TrackMarkerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests Room DAO operations using an in-memory database.
 * Uses ApplicationProvider from AndroidX Test (Robolectric for local JVM unit tests).
 */
@org.robolectric.annotation.Config(sdk = [34])
class TrackMarkerDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RecordingDao

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.recordingDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and retrieve recording`() = runBlocking {
        val recording = RecordingEntity(
            fileName = "2026-06-09_14-30-00_set.flac",
            filePath = "/music/USB DeckRec/2026-06-09_14-30-00_set.flac",
            fileSizeBytes = 42_000_000L,
            durationMs = 3_600_000L,
            sampleRate = 48000,
            bitDepth = 24,
            channelCount = 2,
            format = "FLAC",
            dateCreated = 1_750_000_000_000L,
            mixerModel = "DJM-900NXS2"
        )
        val id = dao.insertRecording(recording)
        assertTrue(id > 0, "Inserted recording should have a valid ID")

        val retrieved = dao.getRecordingById(id)
        assertNotNull(retrieved)
        assertEquals("DJM-900NXS2", retrieved!!.mixerModel)
        assertEquals("FLAC", retrieved.format)
        assertEquals(48000, retrieved.sampleRate)
        assertEquals(24, retrieved.bitDepth)
    }

    @Test
    fun `insert track marker with foreign key`() = runBlocking {
        // Insert a recording first
        val recording = RecordingEntity(
            fileName = "test_session.flac",
            filePath = "/test/test_session.flac",
            fileSizeBytes = 1000L,
            durationMs = 60_000L,
            sampleRate = 48000,
            bitDepth = 24,
            channelCount = 2,
            format = "FLAC",
            dateCreated = System.currentTimeMillis()
        )
        val recordingId = dao.insertRecording(recording)

        // Insert a track marker referencing that recording
        val marker = TrackMarkerEntity(
            recordingId = recordingId,
            positionMs = 30_000L,
            label = "Drop at 0:30",
            midiEventType = "FADER_MOVE"
        )
        val markerId = dao.insertTrackMarker(marker)
        assertTrue(markerId > 0, "Inserted marker should have a valid ID")

        // Retrieve markers for this recording
        val markers = dao.getTrackMarkers(recordingId).first()
        assertEquals(1, markers.size)
        assertEquals(30_000L, markers[0].positionMs)
        assertEquals("Drop at 0:30", markers[0].label)
        assertEquals("FADER_MOVE", markers[0].midiEventType)
    }

    @Test
    fun `delete recording cascades markers`() = runBlocking {
        // Insert a recording
        val recording = RecordingEntity(
            fileName = "cascade_test.flac",
            filePath = "/test/cascade_test.flac",
            fileSizeBytes = 2000L,
            durationMs = 120_000L,
            sampleRate = 48000,
            bitDepth = 24,
            channelCount = 2,
            format = "FLAC",
            dateCreated = System.currentTimeMillis()
        )
        val recordingId = dao.insertRecording(recording)

        // Insert two markers
        dao.insertTrackMarker(
            TrackMarkerEntity(recordingId = recordingId, positionMs = 10_000L, label = "Marker 1")
        )
        dao.insertTrackMarker(
            TrackMarkerEntity(recordingId = recordingId, positionMs = 20_000L, label = "Marker 2")
        )

        // Verify markers exist
        assertEquals(2, dao.getTrackMarkers(recordingId).first().size)

        // Delete the recording (CASCADE should remove markers)
        dao.deleteRecording(recording)

        // Verify recording is gone
        assertNull(dao.getRecordingById(recordingId))

        // Verify markers are also gone (CASCADE)
        assertEquals(0, dao.getTrackMarkers(recordingId).first().size,
            "Track markers should be cascade-deleted with the recording")
    }

    @Test
    fun `get track markers ordered by position`() = runBlocking {
        val recording = RecordingEntity(
            fileName = "ordering_test.flac",
            filePath = "/test/ordering_test.flac",
            fileSizeBytes = 3000L,
            durationMs = 180_000L,
            sampleRate = 48000,
            bitDepth = 24,
            channelCount = 2,
            format = "FLAC",
            dateCreated = System.currentTimeMillis()
        )
        val recordingId = dao.insertRecording(recording)

        // Insert markers out of order
        dao.insertTrackMarker(
            TrackMarkerEntity(recordingId = recordingId, positionMs = 90_000L, label = "Middle")
        )
        dao.insertTrackMarker(
            TrackMarkerEntity(recordingId = recordingId, positionMs = 10_000L, label = "Start")
        )
        dao.insertTrackMarker(
            TrackMarkerEntity(recordingId = recordingId, positionMs = 170_000L, label = "End")
        )

        // Retrieve markers - they should be ordered by positionMs ASC
        val markers = dao.getTrackMarkers(recordingId).first()
        assertEquals(3, markers.size)
        assertEquals("Start", markers[0].label)
        assertEquals(10_000L, markers[0].positionMs)
        assertEquals("Middle", markers[1].label)
        assertEquals(90_000L, markers[1].positionMs)
        assertEquals("End", markers[2].label)
        assertEquals(170_000L, markers[2].positionMs)
    }

    @Test
    fun `getAllRecordings returns recordings ordered by dateCreated DESC`() = runBlocking {
        val recording1 = RecordingEntity(
            fileName = "older.flac",
            filePath = "/test/older.flac",
            fileSizeBytes = 1000L,
            durationMs = 60_000L,
            sampleRate = 48000,
            bitDepth = 24,
            channelCount = 2,
            format = "FLAC",
            dateCreated = 1_000_000L
        )
        val recording2 = RecordingEntity(
            fileName = "newer.flac",
            filePath = "/test/newer.flac",
            fileSizeBytes = 2000L,
            durationMs = 120_000L,
            sampleRate = 48000,
            bitDepth = 24,
            channelCount = 2,
            format = "WAV",
            dateCreated = 2_000_000L
        )

        dao.insertRecording(recording1)
        dao.insertRecording(recording2)

        val allRecordings = dao.getAllRecordings().first()
        assertEquals(2, allRecordings.size)
        // Newer should come first (DESC order)
        assertEquals("newer.flac", allRecordings[0].fileName)
        assertEquals("older.flac", allRecordings[1].fileName)
    }

    @Test
    fun `insert multiple markers for same recording`() = runBlocking {
        val recording = RecordingEntity(
            fileName = "multi_marker.flac",
            filePath = "/test/multi_marker.flac",
            fileSizeBytes = 5000L,
            durationMs = 300_000L,
            sampleRate = 48000,
            bitDepth = 24,
            channelCount = 2,
            format = "FLAC",
            dateCreated = System.currentTimeMillis()
        )
        val recordingId = dao.insertRecording(recording)

        // Insert 5 markers at different positions
        val positions = listOf(10_000L, 30_000L, 60_000L, 120_000L, 240_000L)
        for (pos in positions) {
            dao.insertTrackMarker(
                TrackMarkerEntity(
                    recordingId = recordingId,
                    positionMs = pos,
                    label = "Marker at ${pos}ms"
                )
            )
        }

        val markers = dao.getTrackMarkers(recordingId).first()
        assertEquals(5, markers.size)
        // Verify all positions are present and ordered
        assertEquals(positions, markers.map { it.positionMs })
    }
}
