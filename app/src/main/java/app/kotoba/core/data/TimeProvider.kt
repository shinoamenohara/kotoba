package app.kotoba.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Clock abstraction so scheduling and streak logic are testable. */
interface TimeProvider {
    fun now(): Long
    fun zone(): ZoneId = ZoneId.systemDefault()

    fun today(): Long = epochDay(now())

    fun epochDay(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate().toEpochDay()

    fun startOfDay(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone()).toInstant().toEpochMilli()

    /** Offset that turns a UTC millis into local time — for SQL day grouping. */
    fun zoneOffsetMs(): Long = zone().rules.getOffset(Instant.ofEpochMilli(now())).totalSeconds * 1000L
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
