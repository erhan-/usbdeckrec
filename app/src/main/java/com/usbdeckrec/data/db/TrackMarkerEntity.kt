package com.usbdeckrec.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_markers",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordingId")]
)
data class TrackMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val positionMs: Long,
    val label: String? = null,
    val midiEventType: String? = null,
    val midiData: ByteArray? = null
)
