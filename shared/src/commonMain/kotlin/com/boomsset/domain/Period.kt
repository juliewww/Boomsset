package com.boomsset.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** 净值曲线的时间粒度。 */
enum class Period {
    MONTH,
    QUARTER,
    YEAR,
}

/** 该日期所属周期的第一天。 */
internal fun LocalDate.startOfPeriod(period: Period): LocalDate = when (period) {
    Period.MONTH -> LocalDate(year, month, 1)
    // 季度起始月：1 / 4 / 7 / 10
    // 用 Month 枚举的 ordinal（0 基）算季度起始月，避开已废弃的 monthNumber
    Period.QUARTER -> LocalDate(year, Month.entries[(month.ordinal / 3) * 3], 1)
    Period.YEAR -> LocalDate(year, 1, 1)
}

/** 该日期所属周期的最后一天。 */
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
 * 生成最近 [count] 个周期的取样日期，按时间升序。
 *
 * **最后一个点是 [today] 而不是当前周期的末日** —— 当前周期还没结束，
 * 用未来的日期取样会得到一个和"现在"不符的净值。
 */
internal fun periodSampleDates(today: LocalDate, period: Period, count: Int): List<LocalDate> {
    require(count > 0) { "count 必须为正数，实际是 $count" }
    val dates = mutableListOf<LocalDate>()
    var cursor = today
    repeat(count) {
        val end = cursor.endOfPeriod(period)
        dates += if (end > today) today else end
        cursor = cursor.startOfPeriod(period).minus(1, DateTimeUnit.DAY)
    }
    return dates.asReversed().distinct()
}
