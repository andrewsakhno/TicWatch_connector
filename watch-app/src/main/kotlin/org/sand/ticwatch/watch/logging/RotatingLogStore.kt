package org.sand.ticwatch.watch.logging

import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Filters for [RotatingLogStore.query]; [contains] is a case-insensitive substring match. */
data class LogQuery(
    val from: Instant,
    val to: Instant,
    val contains: String?,
    val maxLines: Int,
)

/** Matching lines (oldest first) - the newest ones when [truncated], so the caller pages backwards via [to]. */
data class LogQueryResult(val lines: List<String>, val truncated: Boolean)

/**
 * App log kept as one file per local hour (`app-yyyyMMdd-HH.log`) so retention is a cheap
 * whole-file delete and a time-range query only opens the hours it covers. Lines are expected
 * to start with logcat's `-v year` timestamp; lines without one (stack-trace continuations)
 * inherit the previous line's time.
 */
class RotatingLogStore(
    private val directory: File,
    private val retention: Duration = DEFAULT_RETENTION,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lock = Any()
    private var openHour: LocalDateTime? = null
    private var writer: Writer? = null

    fun append(line: String, now: Instant = Instant.now()) = synchronized(lock) {
        val hour = LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.HOURS)
        if (hour != openHour) rotate(hour, now)
        writer?.run {
            write(line)
            write(LINE_SEPARATOR)
            flush()
        }
    }

    fun query(query: LogQuery): LogQueryResult {
        val firstHour = LocalDateTime.ofInstant(query.from, zone).truncatedTo(ChronoUnit.HOURS)
        val lastHour = LocalDateTime.ofInstant(query.to, zone).truncatedTo(ChronoUnit.HOURS)
        val needle = query.contains?.takeIf { it.isNotBlank() }?.lowercase()
        val window = ArrayDeque<String>()
        var truncated = false
        logFiles()
            .filter { (hour, _) -> !hour.isBefore(firstHour) && !hour.isAfter(lastHour) }
            .forEach { (_, file) ->
                var lineTime: Instant? = null
                file.forEachLine { line ->
                    lineTime = parseTime(line) ?: lineTime
                    val time = lineTime ?: return@forEachLine
                    if (time < query.from || time >= query.to) return@forEachLine
                    if (needle != null && !line.lowercase().contains(needle)) return@forEachLine
                    window.addLast(line)
                    if (window.size > query.maxLines) {
                        window.removeFirst()
                        truncated = true
                    }
                }
            }
        return LogQueryResult(window.toList(), truncated)
    }

    fun totalBytes(): Long = logFiles().sumOf { (_, file) -> file.length() }

    private fun rotate(hour: LocalDateTime, now: Instant) {
        runCatching { writer?.close() }
        directory.mkdirs()
        writer = File(directory, FILE_PREFIX + FILE_HOUR.format(hour) + FILE_SUFFIX).let { FileWriter(it, true) }
        openHour = hour
        prune(now)
    }

    private fun prune(now: Instant) {
        val oldestKept = LocalDateTime.ofInstant(now.minus(retention), zone).truncatedTo(ChronoUnit.HOURS)
        logFiles().filter { (hour, _) -> hour.isBefore(oldestKept) }.forEach { (_, file) -> file.delete() }
    }

    private fun logFiles(): List<Pair<LocalDateTime, File>> =
        (directory.listFiles() ?: emptyArray())
            .mapNotNull { file -> hourOf(file)?.let { it to file } }
            .sortedBy { it.first }

    private fun hourOf(file: File): LocalDateTime? {
        val name = file.name
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        return runCatching {
            LocalDateTime.parse(name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX), FILE_HOUR)
        }.getOrNull()
    }

    private fun parseTime(line: String): Instant? {
        if (line.length < LINE_TIME_LENGTH) return null
        return runCatching {
            LocalDateTime.parse(line.substring(0, LINE_TIME_LENGTH), LINE_TIME).atZone(zone).toInstant()
        }.getOrNull()
    }

    companion object {
        const val DIRECTORY_NAME = "logs"

        fun inFilesDir(filesDir: File) = RotatingLogStore(File(filesDir, DIRECTORY_NAME))

        val DEFAULT_RETENTION: Duration = Duration.ofDays(3)
        private const val FILE_PREFIX = "app-"
        private const val FILE_SUFFIX = ".log"
        private const val LINE_SEPARATOR = "\n"
        private val FILE_HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HH")

        // logcat `-v year` prefix, e.g. "2026-10-26 14:03:22.123".
        private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private const val LINE_TIME_LENGTH = 23
    }
}
