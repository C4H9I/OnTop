package com.ontop.app.reminders

import com.ontop.app.data.ReminderEntity

/** Editor state only: deliberately has no card ID, rule ID or pending alarm timestamps. */
internal data class ReminderDraft(
    val schedule: ReminderSchedule = ReminderSchedule(),
    val enabled: Boolean = false,
    val pausedUntil: Long? = null
) : java.io.Serializable

internal fun ReminderEntity.draft() = ReminderDraft(schedule(), enabled, pausedUntil)
