package app.kotoba.feature.notify

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class ReminderScheduleTest {
    private val now = LocalDateTime.of(2026, 11, 9, 18, 30)

    @Test fun `later today`() {
        assertThat(delayUntil(now, 19, 0)).isEqualTo(Duration.ofMinutes(30))
    }

    @Test fun `already passed or exactly now moves to tomorrow`() {
        assertThat(delayUntil(now, 8, 0)).isEqualTo(Duration.ofHours(13).plusMinutes(30))
        assertThat(delayUntil(now, 18, 30)).isEqualTo(Duration.ofDays(1))
    }
}
