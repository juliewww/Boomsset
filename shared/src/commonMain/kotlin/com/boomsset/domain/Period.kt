package com.boomsset.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The time granularity of the net worth curve. */
enum class Period {
    MONTH,
    QUARTER,
    YEAR,
}

/** The first day of the period this date falls in. */
internal fun LocalDate.startOfPeriod(period: Period): LocalDate = when (period) {
    Period.MONTH -> LocalDate(year, month, 1)
    // Quarter start months: 1 / 4 / 7 / 10
    // Uses the Month enum's ordinal (0-based) to compute the quarter start month, avoiding the deprecated monthNumber
    Period.QUARTER -> LocalDate(year, Month.entries[(month.ordinal / 3) * 3], 1)
    Period.YEAR -> LocalDate(year, 1, 1)
}

/** The last day of the period this date falls in. */
internal fun LocalDate.endOfPeriod(period: Period): LocalDate {
    val start = startOfPeriod(period)
    val nextStart = when (period) {
        Period.MONTH -> start.plus(1, DateTimeUnit.MONTH)
        Period.QUARTER -> start.plus(3, DateTimeUnit.MONTH)
        Period.YEAR -> start.plus(1, DateTimeUnit.YEAR)
    }
    return nextStart.minus(1, DateTimeUnit.DAY)
}

/**
 * Generates the sample dates for the most recent [count] periods, in ascending time order.
 *
 * **The last point is [today], not the end of the current period** — the current
 * period hasn't ended yet, so sampling on a future date would produce a net worth value
 * that doesn't match "right now".
 */
internal fun periodSampleDates(today: LocalDate, period: Period, count: Int): List<LocalDate> {
    require(count > 0) { "count must be positive, but was $count" }
    val dates = mutableListOf<LocalDate>()
    var cursor = today
    repeat(count) {
        val end = cursor.endOfPeriod(period)
        dates += if (end > today) today else end
        cursor = cursor.startOfPeriod(period).minus(1, DateTimeUnit.DAY)
    }
    return dates.asReversed().distinct()
}
