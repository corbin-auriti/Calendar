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
import androidx.core.content.ContextCompat
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
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.helpers.ensureBackgroundThread
import java.time.LocalDate

class ReminderActivity : AppCompatActivity() {
    companion object {
        private val MINUTE_MARKERS = intArrayOf(1, 5, 10, 15, 30)
        private val HOUR_MARKERS = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 18, 24, 36, 48)
        private val DAY_MARKERS = intArrayOf(1, 2, 3, 4, 5, 7, 10, 14, 21, 30, 45, 60, 90, 180, 365, 730)
        private const val MARKER_GROWTH = 1.35f
        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 1440
        private const val TEST_MINUTES_TO_EVENT = 4320
        private const val MIN_SNOOZE_MARKER = 1
        private const val STEPPER_DISABLED_ALPHA = 0.3f
        private const val STEPPER_ENABLED_ALPHA = 1f
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
    private var snoozeMarker = MIN_SNOOZE_MARKER
    private var snoozeMarkers = listOf(MIN_SNOOZE_MARKER)
    private var minutesToEvent = 0
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
        val usingSystemTheme = isSystemThemeActive()
        if (usingSystemTheme) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        val accent = if (usingSystemTheme) {
            pickDynamicAccent()
        } else {
            getProperPrimaryColor()
        }
        binding.reminderStar.setStarColor(accent)
        binding.reminderDismissHint.setTextColor(accent)

        val backgroundColor = ColorUtils.blendARGB(accent, getProperBackgroundColor(), BACKGROUND_ACCENT_RATIO)
        val background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.blendARGB(backgroundColor, Color.BLACK, DARKEN_RATIO), backgroundColor)
        )
        binding.root.background = background
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val textColor = getProperTextColor()
        binding.reminderTitle.setTextColor(textColor)
        binding.reminderTime.setTextColor(textColor.adjustAlpha(TERTIARY_TEXT_ALPHA))
        binding.reminderLocation.setTextColor(textColor.adjustAlpha(TERTIARY_TEXT_ALPHA))
        binding.reminderDescription.setTextColor(textColor.adjustAlpha(TERTIARY_TEXT_ALPHA))
        binding.reminderStar.setRangeCircleColor(
            ColorUtils.blendARGB(backgroundColor, accent, RANGE_TINT_RATIO)
        )
    }

    private fun isSystemThemeActive(): Boolean {
        if (!config.isSystemThemeEnabled) {
            return false
        }
        return config.textColor == ContextCompat.getColor(this, org.fossify.commons.R.color.default_text_color) &&
            config.backgroundColor ==
            ContextCompat.getColor(this, org.fossify.commons.R.color.default_background_color) &&
            config.primaryColor == ContextCompat.getColor(this, org.fossify.commons.R.color.default_primary_color) &&
            config.accentColor == ContextCompat.getColor(this, org.fossify.commons.R.color.default_accent_color)
    }

    private fun pickDynamicAccent(): Int {
        return try {
            val primary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary)
            val primaryContainer =
                MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
            if (accentScore(primaryContainer) > accentScore(primary)) primaryContainer else primary
        } catch (e: IllegalArgumentException) {
            getProperPrimaryColor()
        }
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

    private fun generateMarkers(maxMinutes: Int): List<Int> {
        val markers = sortedSetOf(MIN_SNOOZE_MARKER)
        MINUTE_MARKERS.forEach { if (it in MIN_SNOOZE_MARKER until maxMinutes) markers.add(it) }
        HOUR_MARKERS.forEach {
            val minutes = it * MINUTES_PER_HOUR
            if (minutes in MIN_SNOOZE_MARKER until maxMinutes) markers.add(minutes)
        }
        DAY_MARKERS.forEach {
            val minutes = it * MINUTES_PER_DAY
            if (minutes in MIN_SNOOZE_MARKER until maxMinutes) markers.add(minutes)
        }

        var value = MIN_SNOOZE_MARKER.toFloat()
        while (value < maxMinutes) {
            val rounded = roundToResolution(value)
            if (rounded in MIN_SNOOZE_MARKER until maxMinutes) {
                markers.add(rounded)
            }
            value *= MARKER_GROWTH
        }
        return markers.toList()
    }

    private fun roundToResolution(value: Float): Int {
        val resolution = Math.pow(10.0, Math.floor(Math.log10(value.toDouble())).toDouble()).toFloat()
        val step = Math.round(value / resolution)
        return (step * resolution).toInt().coerceAtLeast(MIN_SNOOZE_MARKER)
    }

    private fun buildSnoozeSteps(minutesUntilEvent: Int) {
        minutesToEvent = minutesUntilEvent
        snoozeMarkers = generateMarkers(minutesUntilEvent)
        val desiredMarker = (minutesUntilEvent - config.snoozeTime).coerceAtLeast(MIN_SNOOZE_MARKER)
        val index = snoozeMarkers.indexOfFirst { it >= desiredMarker }
        snoozeMarker = if (index == -1) {
            snoozeMarkers.last()
        } else {
            snoozeMarkers[index]
        }
        updateSnoozeLabel()
    }

    private fun stepDown() {
        val index = snoozeMarkers.indexOf(snoozeMarker)
        if (index > 0) {
            snoozeMarker = snoozeMarkers[index - 1]
            updateSnoozeLabel()
        }
    }

    private fun stepUp() {
        val index = snoozeMarkers.indexOf(snoozeMarker)
        if (index in 0 until snoozeMarkers.lastIndex) {
            snoozeMarker = snoozeMarkers[index + 1]
            updateSnoozeLabel()
        }
    }

    private fun setupTestContent() {
        binding.reminderTitle.text = getString(R.string.test_fullscreen_reminder_title)
        binding.reminderTime.text = getString(R.string.test_fullscreen_reminder_time)
        binding.reminderLocation.text = getString(R.string.test_fullscreen_reminder_location)
        binding.reminderLocation.visibility = View.VISIBLE
        binding.reminderDescription.text = getString(R.string.test_fullscreen_reminder_description)
        binding.reminderDescription.visibility = View.VISIBLE
        buildSnoozeSteps(TEST_MINUTES_TO_EVENT)
    }

    private fun updateSnoozeLabel() {
        binding.reminderSnooze.text = when {
            snoozeMarker >= MINUTES_PER_DAY && snoozeMarker % MINUTES_PER_DAY == 0 ->
                getString(R.string.snooze_days_before, snoozeMarker / MINUTES_PER_DAY)

            snoozeMarker >= MINUTES_PER_HOUR && snoozeMarker % MINUTES_PER_HOUR == 0 ->
                getString(R.string.snooze_hours_before, snoozeMarker / MINUTES_PER_HOUR)

            else -> getString(R.string.snooze_minutes_before, snoozeMarker)
        }

        val index = snoozeMarkers.indexOf(snoozeMarker)
        val canStepDown = index > 0
        binding.reminderSnoozeMinus.isEnabled = canStepDown
        binding.reminderSnoozeMinus.alpha =
            if (canStepDown) STEPPER_ENABLED_ALPHA else STEPPER_DISABLED_ALPHA
        val canStepUp = index in 0 until snoozeMarkers.lastIndex
        binding.reminderSnoozePlus.isEnabled = canStepUp
        binding.reminderSnoozePlus.alpha =
            if (canStepUp) STEPPER_ENABLED_ALPHA else STEPPER_DISABLED_ALPHA
    }

    private fun snoozeReminder() {
        finish()
        if (isTestReminder) {
            return
        }
        val snoozeDuration = (minutesToEvent - snoozeMarker).coerceAtLeast(1)
        ensureBackgroundThread {
            val event = eventsDB.getEventOrTaskWithId(eventId)
            if (event != null) {
                rescheduleReminder(event, snoozeDuration)
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
