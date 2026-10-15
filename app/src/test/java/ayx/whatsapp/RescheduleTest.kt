package ayx.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for [Reschedule]'s pure logic (no Android APIs):
 * candidate heuristic, tolerant LLM-JSON extraction, future-datetime
 * validation, and Hindi schedule formatting.
 *
 * There is deliberately no command prefix: detection is heuristic + AI.
 */
class RescheduleTest {

    private fun utcMs(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.set(y, mo - 1, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ------------------------------------------------------- candidate heuristic

    @Test fun candidate_hindiNatural() {
        assertTrue(Reschedule.isReminderCandidate("bhai mujhe kal subah 8 baje good morning message karna"))
    }

    @Test fun candidate_hindiShort() {
        assertTrue(Reschedule.isReminderCandidate("mujhe kal raat 9 baje message karna"))
    }

    @Test fun candidate_english() {
        assertTrue(Reschedule.isReminderCandidate("remind me tomorrow at 9pm"))
    }

    @Test fun candidate_yaadDila() {
        assertTrue(Reschedule.isReminderCandidate("yaad dila dena kal"))
    }

    @Test fun candidate_selfNoTime() {
        // Verb + self-reference, no time: candidate — the AI will ask for the time.
        assertTrue(Reschedule.isReminderCandidate("mujhe message karna"))
    }

    @Test fun candidate_caseInsensitive() {
        assertTrue(Reschedule.isReminderCandidate("MUJHE KAL 9 BAJE MESSAGE KARNA"))
    }

    @Test fun notCandidate_plainChat() {
        assertFalse(Reschedule.isReminderCandidate("kal 9 baje milte hain"))
    }

    @Test fun notCandidate_instructionToOther() {
        // "usko message karna" = instruction about someone else, no time/self ref.
        assertFalse(Reschedule.isReminderCandidate("usko message karna"))
    }

    @Test fun notCandidate_greeting() {
        assertFalse(Reschedule.isReminderCandidate("good morning!"))
    }

    @Test fun notCandidate_empty() {
        assertFalse(Reschedule.isReminderCandidate(""))
        assertFalse(Reschedule.isReminderCandidate("   "))
    }

    // ------------------------------------------------------- extraction parsing

    @Test fun extraction_validJson() {
        val e = Reschedule.parseExtractionJson(
            """{"has_reminder": true, "datetime": "2026-10-16T21:00", "action": "good night message", "confidence": 0.95, "clarification": ""}"""
        )!!
        assertTrue(e.hasReminder)
        assertEquals("2026-10-16T21:00", e.datetime)
        assertEquals("good night message", e.action)
        assertEquals(0.95, e.confidence, 0.0001)
    }

    @Test fun extraction_fencedJson() {
        val e = Reschedule.parseExtractionJson(
            "```json\n{\"has_reminder\": true, \"datetime\": \"2026-10-16T08:00\", \"action\": \"gm\", \"confidence\": 0.9, \"clarification\": \"\"}\n```"
        )!!
        assertEquals("2026-10-16T08:00", e.datetime)
    }

    @Test fun extraction_withProse() {
        val e = Reschedule.parseExtractionJson(
            "Here you go: {\"has_reminder\": false, \"datetime\": \"\", \"action\": \"\", \"confidence\": 0.2, \"clarification\": \"Time samajh nahi aaya\"} hope this helps"
        )!!
        assertFalse(e.hasReminder)
        assertEquals("Time samajh nahi aaya", e.clarification)
    }

    @Test fun extraction_garbage() {
        assertNull(Reschedule.parseExtractionJson(""))
        assertNull(Reschedule.parseExtractionJson("definitely not json"))
        assertNull(Reschedule.parseExtractionJson("{\"foo\": 1}"))
    }

    // ------------------------------------------------------- datetime validation
    // Asia/Kolkata is forced by the Gradle test task (see README); utcMs builds
    // instants independent of the default zone.

    @Test fun future_ok() {
        val now = utcMs(2026, 10, 15, 12, 0) // 17:30 IST
        // "2026-10-16T21:00" IST == 15:30 UTC
        assertEquals(utcMs(2026, 10, 16, 15, 30),
            Reschedule.validateFutureDatetime("2026-10-16T21:00", now))
    }

    @Test fun future_pastRejected() {
        val now = utcMs(2026, 10, 15, 12, 0)
        assertNull(Reschedule.validateFutureDatetime("2026-10-15T10:00", now))
    }

    @Test fun future_tooSoonRejected() {
        val now = utcMs(2026, 10, 15, 12, 0)
        assertNull(Reschedule.validateFutureDatetime("2026-10-15T17:30", now)) // == now
        assertNull(Reschedule.validateFutureDatetime("2026-10-15T17:30", now, minAheadMs = 3_600_000))
    }

    @Test fun future_malformedRejected() {
        val now = utcMs(2026, 10, 15, 12, 0)
        assertNull(Reschedule.validateFutureDatetime("tomorrow 9pm", now))
        assertNull(Reschedule.validateFutureDatetime("", now))
        assertNull(Reschedule.validateFutureDatetime("2026-13-45T99:99", now))
    }

    // ------------------------------------------------------- Hindi formatting

    @Test fun format_tomorrowNight() {
        val now = utcMs(2026, 10, 15, 12, 0) // 17:30 IST Oct 15
        assertEquals("kal raat 9 baje",
            Reschedule.formatScheduleHin(utcMs(2026, 10, 16, 15, 30), now)) // 21:00 IST Oct 16
    }

    @Test fun format_todayMorning() {
        val now = utcMs(2026, 10, 15, 0, 0)
        assertEquals("aaj subah 8 baje",
            Reschedule.formatScheduleHin(utcMs(2026, 10, 15, 2, 30), now)) // 08:00 IST
    }

    @Test fun format_withMinutes() {
        val now = utcMs(2026, 10, 15, 0, 0)
        assertEquals("aaj shaam 6:30 baje",
            Reschedule.formatScheduleHin(utcMs(2026, 10, 15, 13, 0), now)) // 18:30 IST
    }

    @Test fun format_farFuture() {
        val now = utcMs(2026, 10, 15, 12, 0)
        assertEquals("20 Oct ko dopahar 2 baje",
            Reschedule.formatScheduleHin(utcMs(2026, 10, 20, 8, 30), now)) // 14:00 IST Oct 20
    }

    @Test fun format_noon() {
        val now = utcMs(2026, 10, 15, 0, 0)
        assertEquals("aaj dopahar 12 baje",
            Reschedule.formatScheduleHin(utcMs(2026, 10, 15, 6, 30), now)) // 12:00 IST
    }
}
