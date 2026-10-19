package com.ontop.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "reminders", foreignKeys = [ForeignKey(entity = CardEntity::class,
    parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["cardId"], unique = true)])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val nextAt: Long? = null,
    val snoozeAt: Long? = null,
    @ColumnInfo(defaultValue = "'TIMES'") val mode: String = "TIMES",
    @ColumnInfo(defaultValue = "127") val daysMask: Int = 127,
    // Empty is the migrated daily rule: use hour/minute without rewriting its alarm.
    @ColumnInfo(defaultValue = "''") val times: String = "",
    @ColumnInfo(defaultValue = "3") val intervalHours: Int = 3,
    @ColumnInfo(defaultValue = "540") val windowStart: Int = 540,
    @ColumnInfo(defaultValue = "1260") val windowEnd: Int = 1260,
    val quietStart: Int? = null,
    val quietEnd: Int? = null,
    val pausedUntil: Long? = null
)
