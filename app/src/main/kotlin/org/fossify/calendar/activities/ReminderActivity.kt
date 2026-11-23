package org.fossify.calendar.activities

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import org.fossify.calendar.R
import org.fossify.calendar.databinding.ActivityReminderBinding
import org.fossify.calendar.extensions.cancelNotification
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.rescheduleReminder
import org.fossify.calendar.helpers.EVENT_ID
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.models.Event
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.helpers.ensureBackgroundThread
import java.time.LocalDate

class ReminderActivity : AppCompatActivity() {
    companion object {
        private const val DEFAULT_SNOOZE_MINUTES = 5
        private const val SNOOZE_STEP_MINUTES = 5
        private const val MIN_SNOOZE_MINUTES = 5
        private const val MAX_SNOOZE_MINUTES = 120
        private const val TEST_REMINDER = "test_reminder"
        private const val BACKGROUND_ACCENT_RATIO = 0.88f
        private const val TERTIARY_TEXT_ALPHA = 0.8f
        private const val DARKEN_RATIO = 0.22f
        private const val RANGE_TINT_RATIO = 0.12f
        private const val MID_LUMINANCE = 0.5f
        private const val HTSL_COMPONENTS = 3
        private const val SATURATION_INDEX = 1

        fun launchTest(context: Context) {
            context.startActivity(
                Intent(context, ReminderActivity::class.java)
                    .putExtra(TEST_REMINDER, true)
            )
        }
    }

    private lateinit var binding: ActivityReminderBinding
    private var snoozeMinutes = DEFAULT_SNOOZE_MINUTES
    private var eventId = 0L
    private var isTestReminder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyDynamicColors()

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
        isTestReminder = intent.getBooleanExtra(TEST_REMINDER, false)

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

        if (isTestReminder) {
            setupTestContent()
        } else {
            ensureBackgroundThread {
                val event = if (eventId != 0L) eventsDB.getEventOrTaskWithId(eventId) else null
                runOnUiThread {
                    if (event == null) {
                        finish()
                        return@runOnUiThread
                    }
                    setupEventContent(event)
                }
            }
        }
    }

    private fun applyDynamicColors() {
        DynamicColors.applyToActivityIfAvailable(this)
        val accent = pickDynamicAccent()
        binding.reminderStar.setStarColor(accent)
        binding.reminderDismissHint.setTextColor(accent)

        val backgroundColor = ColorUtils.blendARGB(accent, config.backgroundColor, BACKGROUND_ACCENT_RATIO)
        val background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.blendARGB(backgroundColor, Color.BLACK, DARKEN_RATIO), backgroundColor)
        )
        binding.root.background = background
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val textColor = config.textColor
        binding.reminderTitle.setTextColor(textColor)
        binding.reminderTime.setTextColor(textColor.adjustAlpha(TERTIARY_TEXT_ALPHA))
        binding.reminderLocation.setTextColor(textColor.adjustAlpha(TERTIARY_TEXT_ALPHA))
        binding.reminderDescription.setTextColor(textColor.adjustAlpha(TERTIARY_TEXT_ALPHA))
        binding.reminderStar.setRangeCircleColor(
            ColorUtils.blendARGB(backgroundColor, accent, RANGE_TINT_RATIO)
        )
    }

    private fun pickDynamicAccent(): Int {
        val primary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary)
        val primaryContainer =
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
        return if (accentScore(primaryContainer) > accentScore(primary)) primaryContainer else primary
    }

    private fun accentScore(color: Int): Float {
        val hsl = FloatArray(HTSL_COMPONENTS)
        ColorUtils.colorToHSL(color, hsl)
        val luminance = ColorUtils.calculateLuminance(color).toFloat()
        return hsl[SATURATION_INDEX] * (1f - Math.abs(luminance - MID_LUMINANCE))
    }

    private fun setupEventContent(event: Event) {
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

        if (event.location.isNotEmpty()) {
            binding.reminderLocation.text = event.location
            binding.reminderLocation.visibility = View.VISIBLE
        }
        if (event.description.isNotEmpty()) {
            binding.reminderDescription.text = event.description
            binding.reminderDescription.visibility = View.VISIBLE
        }
    }

    private fun setupTestContent() {
        binding.reminderTitle.text = getString(R.string.test_fullscreen_reminder_title)
        binding.reminderTime.text = getString(R.string.test_fullscreen_reminder_time)
        binding.reminderDescription.text = getString(R.string.test_fullscreen_reminder_description)
        binding.reminderDescription.visibility = View.VISIBLE
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
        if (isTestReminder) {
            return
        }
        ensureBackgroundThread {
            val event = eventsDB.getEventOrTaskWithId(eventId)
            if (event != null) {
                rescheduleReminder(event, snoozeMinutes)
            }
        }
    }

    private fun dismissReminder() {
        if (!isTestReminder) {
            cancelNotification(eventId)
        }
        finish()
    }
}
