package org.fossify.calendar.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.fossify.calendar.R
import org.fossify.calendar.databinding.ActivityReminderBinding
import org.fossify.calendar.extensions.cancelNotification
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.helpers.EVENT_ID
import org.fossify.calendar.helpers.Formatter
import org.fossify.commons.helpers.ensureBackgroundThread
import java.time.LocalDate

class ReminderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReminderBinding

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

        val eventId = intent.getLongExtra(EVENT_ID, 0L)
        ensureBackgroundThread {
            val event = eventsDB.getEventOrTaskWithId(eventId)
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
                    else -> "${Formatter.getDateFromCode(this, Formatter.getDayCodeFromTS(event.startTS))},"
                }
                val timeRange = if (event.getIsAllDay()) {
                    getString(R.string.all_day)
                } else {
                    "${Formatter.getTimeFromTS(this, event.startTS)} – ${Formatter.getTimeFromTS(this, event.endTS)}"
                }
                binding.reminderTime.text = "$displayedStartDate $timeRange".trim()

                binding.reminderDismiss.setOnClickListener {
                    cancelNotification(eventId)
                    finish()
                }

                binding.reminderSnooze.setOnClickListener {
                    startActivity(Intent(this, SnoozeReminderActivity::class.java).putExtra(EVENT_ID, eventId))
                    finish()
                }
            }
        }
    }
}
