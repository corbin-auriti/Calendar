package org.fossify.calendar.activities

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.fossify.calendar.R
import org.fossify.calendar.databinding.ActivityReminderBinding
import org.fossify.calendar.extensions.cancelNotification
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.rescheduleReminder
import org.fossify.calendar.helpers.EVENT_ID
import org.fossify.calendar.helpers.Formatter
import org.fossify.commons.helpers.ensureBackgroundThread
import java.time.LocalDate

class ReminderActivity : AppCompatActivity() {
    private companion object {
        const val DEFAULT_SNOOZE_MINUTES = 5
        const val SNOOZE_STEP_MINUTES = 5
        const val MIN_SNOOZE_MINUTES = 5
        const val MAX_SNOOZE_MINUTES = 120
    }

    private lateinit var binding: ActivityReminderBinding
    private var snoozeMinutes = DEFAULT_SNOOZE_MINUTES
    private var eventId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        eventId = intent.getLongExtra(EVENT_ID, 0L)

        binding.reminderSnoozeMinus.setOnClickListener {
            adjustSnooze(-SNOOZE_STEP_MINUTES)
        }
        binding.reminderSnoozePlus.setOnClickListener {
            adjustSnooze(SNOOZE_STEP_MINUTES)
        }
        binding.reminderSnooze.setOnClickListener {
            snoozeReminder()
        }
        binding.reminderStar.dismissal = {
            dismissReminder()
        }
        updateSnoozeLabel()

        ensureBackgroundThread {
            val event = if (eventId != 0L) eventsDB.getEventOrTaskWithId(eventId) else null
            runOnUiThread {
                if (event == null) {
                    finish()
                    return@runOnUiThread
                }

                binding.reminderTitle.text = event.title
                val startDate = Formatter.getDateFromTS(event.startTS)
                val displayedStartDate = when (startDate) {
                    LocalDate.now() -> ""
                    LocalDate.now().plusDays(1) ->
                        getString(org.fossify.commons.R.string.tomorrow)

                    else ->
                        "${Formatter.getDateFromCode(this, Formatter.getDayCodeFromTS(event.startTS))},"
                }
                val timeRange = if (event.getIsAllDay()) {
                    getString(R.string.all_day)
                } else {
                    val startTime = Formatter.getTimeFromTS(this, event.startTS)
                    val endTime = Formatter.getTimeFromTS(this, event.endTS)
                    if (startTime == endTime) startTime else "$startTime \u2013 $endTime"
                }
                binding.reminderTime.text = "$displayedStartDate $timeRange".trim()
            }
        }
    }

    private fun adjustSnooze(deltaMinutes: Int) {
        val newMinutes = (snoozeMinutes + deltaMinutes).coerceIn(MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES)
        if (newMinutes != snoozeMinutes) {
            snoozeMinutes = newMinutes
            updateSnoozeLabel()
        }
    }

    private fun updateSnoozeLabel() {
        binding.reminderSnooze.text = getString(R.string.snooze_minutes, snoozeMinutes)
    }

    private fun snoozeReminder() {
        finish()
        ensureBackgroundThread {
            val event = eventsDB.getEventOrTaskWithId(eventId)
            if (event != null) {
                rescheduleReminder(event, snoozeMinutes)
            }
        }
    }

    private fun dismissReminder() {
        cancelNotification(eventId)
        finish()
    }
}
