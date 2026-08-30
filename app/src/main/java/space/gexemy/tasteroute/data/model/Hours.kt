package space.gexemy.tasteroute.data.model

import java.util.Calendar
import java.util.Locale

/**
 * One venue's week. Times are minutes past midnight, and an interval ending beyond 1440 runs into
 * the next day — which is the only way a 22:00-02:00 kitchen can be stored if "open now" is to be
 * true at one in the morning rather than false all evening.
 */
data class OpeningSchedule(val lines: List<String>, val openNow: Boolean)

/**
 * OpenStreetMap's `opening_hours` tag, made readable.
 *
 * The raw value is a machine syntax — `Mo-Th,Su 11:00-22:00; Fr-Sa 11:00-23:00; PH off` — and it
 * was being printed at people verbatim. This turns it into day lines and, far more usefully,
 * answers the only question anyone actually has when they open a restaurant at 9pm.
 *
 * Deliberately a SUBSET of the specification: day selectors, time spans, `off`, and `24/7`.
 * Month ranges, week numbers, `sunrise`, quoted comments and holiday rules are not parsed, and
 * anything containing them returns null so the caller prints the original string. A half-parsed
 * schedule that silently drops a rule is worse than a cryptic one — "Closed" shown about an open
 * restaurant is a wasted journey, and the app has no way to know it got it wrong.
 */
object Hours {

    private val NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private const val DAY = 24 * 60

    private const val CODE = "(?:Mo|Tu|We|Th|Fr|Sa|Su)"
    private val DAY_SPEC = Regex("^$CODE(?:\\s*-\\s*$CODE)?(?:\\s*,\\s*$CODE(?:\\s*-\\s*$CODE)?)*")
    private val SPAN = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$")
    private val CODES = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    fun parse(raw: String?): OpeningSchedule? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text == "24/7") return OpeningSchedule(listOf("Every day  Open 24 hours"), true)

        val week = Array(7) { mutableListOf<Pair<Int, Int>>() }
        var understood = false
        for (rule in text.split(";")) {
            val r = rule.trim()
            if (r.isEmpty()) continue
            // A holiday rule says nothing about this week, so skipping it loses no information.
            if (r.startsWith("PH") || r.startsWith("SH")) continue
            val spec = DAY_SPEC.find(r)?.value
            val days = spec?.let(::daysOf) ?: (0..6).toList()
            val times = r.removePrefix(spec.orEmpty()).trim()
            if (times.equals("off", true) || times.equals("closed", true)) {
                understood = true
                continue
            }
            if (times.isEmpty()) return null
            val spans = times.split(",").map { part ->
                val m = SPAN.matchEntire(part.trim()) ?: return null
                val opens = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
                var closes = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
                // 22:00-02:00 closes tomorrow. Left as a backwards range it would never contain
                // any moment at all, and every late-night kitchen would read as closed.
                if (closes <= opens) closes += DAY
                opens to closes
            }
            days.forEach { week[it] += spans }
            understood = true
        }
        if (!understood) return null
        week.forEach { it.sortBy { span -> span.first } }
        return OpeningSchedule(lines(week), openNow(week))
    }

    private fun daysOf(spec: String): List<Int> = spec.split(",").flatMap { part ->
        val ends = part.split("-").map { CODES.indexOf(it.trim()) }
        when {
            ends.any { it < 0 } -> emptyList()
            ends.size == 1 -> listOf(ends[0])
            // Fr-Mo wraps around the end of the week; it does not mean nothing.
            else -> generateSequence(ends[0]) { d -> if (d == ends[1]) null else (d + 1) % 7 }.toList()
        }
    }.distinct()

    private fun lines(week: Array<MutableList<Pair<Int, Int>>>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < 7) {
            var j = i
            while (j + 1 < 7 && week[j + 1] == week[i]) j++
            val label = if (i == j) NAMES[i] else "${NAMES[i]}–${NAMES[j]}"
            out += "$label  ${describe(week[i])}"
            i = j + 1
        }
        return out
    }

    private fun describe(spans: List<Pair<Int, Int>>): String = when {
        spans.isEmpty() -> "Closed"
        spans.size == 1 && spans[0].first == 0 && spans[0].second >= DAY -> "Open 24 hours"
        else -> spans.joinToString(", ") { "${clock(it.first)}–${clock(it.second)}" }
    }

    private fun clock(minutes: Int): String {
        val m = minutes % DAY
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    private fun openNow(week: Array<MutableList<Pair<Int, Int>>>): Boolean {
        val cal = Calendar.getInstance()
        // Calendar counts from Sunday = 1; this list counts from Monday = 0.
        val today = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (week[today].any { minute >= it.first && minute < it.second }) return true
        // Somewhere that opened at 22:00 yesterday and closes at 02:00 is open right now at 1am.
        val yesterday = (today + 6) % 7
        return week[yesterday].any { it.second > DAY && minute < it.second - DAY }
    }
}
