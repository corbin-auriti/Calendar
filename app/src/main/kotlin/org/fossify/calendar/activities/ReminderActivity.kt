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
        private val SNOOZE_STEP_MINUTES = listOf(
            5, 10, 15, 30, 45, 60, 90, 120, 180, 240, 300,
            480, 600, 720, 1080, 1200, 1440, 2160, 2880, 4320
        )
        private const val STEPPER_DISABLED_ALPHA = 0.3f
        private const val STEPPER_ENABLED_ALPHA = 1f
        private const val MINUTES_PER_HOUR = 60
        private const val TEST_REMINDER = "test_reminder"
        private const val BACKGROUND_ACCENT_RATIO = 0.88f
        private const val TERTIARY_TEXT_ALPHA = 0.8f
        private const val DARKEN_RATIO = 0.22f
        private const val RANGE_TINT_RATIO = 0.12f
        private const val MID_LUMINANCE = 0.5f
        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLISECONDS_PER_SECOND = 1000
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
    private var snoozeMinutes = SNOOZE_STEP_MINUTES.first()
    private var snoozeSteps = SNOOZE_STEP_MINUTES
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
            stepDown()
        }
        binding.reminderSnoozePlus.setOnClickListener {
            stepUp()
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
                    buildSnoozeSteps(minutesUntilEvent(event))
                }
            }
        }
    }

    private fun applyDynamicColors() {
        val usingSystemTheme = config.isSystemThemeEnabled
        if (usingSystemTheme) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        val accent = if (usingSystemTheme) {
            pickDynamicAccent()
        } else {
            config.accentColor
        }
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

    private fun minutesUntilEvent(event: Event): Int {
        val nowSeconds = System.currentTimeMillis() / MILLISECONDS_PER_SECOND
        return ((event.startTS - nowSeconds) / SECONDS_PER_MINUTE).toInt().coerceAtLeast(0)
    }

    private fun buildSnoozeSteps(minutesToEvent: Int) {
        snoozeSteps = SNOOZE_STEP_MINUTES.filter { it < minutesToEvent }
        if (snoozeSteps.isEmpty()) {
            snoozeSteps = listOf(SNOOZE_STEP_MINUTES.first())
        }
        snoozeMinutes = snoozeSteps.first()
        updateSnoozeLabel()
    }

    private fun stepDown() {
        val index = snoozeSteps.indexOf(snoozeMinutes)
        if (index > 0) {
            snoozeMinutes = snoozeSteps[index - 1]
            updateSnoozeLabel()
        }
    }

    private fun stepUp() {
        val index = snoozeSteps.indexOf(snoozeMinutes)
        if (index in 0 until snoozeSteps.lastIndex) {
            snoozeMinutes = snoozeSteps[index + 1]
            updateSnoozeLabel()
        }
    }

    private fun setupTestContent() {
        snoozeSteps = SNOOZE_STEP_MINUTES
        snoozeMinutes = snoozeSteps.first()
        binding.reminderTitle.text = getString(R.string.test_fullscreen_reminder_title)
        binding.reminderTime.text = getString(R.string.test_fullscreen_reminder_time)
        binding.reminderDescription.text = getString(R.string.test_fullscreen_reminder_description)
        binding.reminderDescription.visibility = View.VISIBLE
    }

    private fun updateSnoozeLabel() {
        if (snoozeMinutes >= MINUTES_PER_HOUR && snoozeMinutes % MINUTES_PER_HOUR == 0) {
            binding.reminderSnooze.text = getString(R.string.snooze_hours, snoozeMinutes / MINUTES_PER_HOUR)
        } else {
            binding.reminderSnooze.text = getString(R.string.snooze_minutes, snoozeMinutes)
        }

        val index = snoozeSteps.indexOf(snoozeMinutes)
        val canStepDown = index > 0
        binding.reminderSnoozeMinus.isEnabled = canStepDown
        binding.reminderSnoozeMinus.alpha =
            if (canStepDown) STEPPER_ENABLED_ALPHA else STEPPER_DISABLED_ALPHA
        val canStepUp = index in 0 until snoozeSteps.lastIndex
        binding.reminderSnoozePlus.isEnabled = canStepUp
        binding.reminderSnoozePlus.alpha =
            if (canStepUp) STEPPER_ENABLED_ALPHA else STEPPER_DISABLED_ALPHA
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
