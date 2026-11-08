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
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxHourBytes: Long = DEFAULT_MAX_HOUR_BYTES,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lock = Any()
    private var openHour: LocalDateTime? = null
    private var writer: Writer? = null
    private var openHourBytes = 0L

    fun append(line: String, now: Instant = Instant.now()) = synchronized(lock) {
        val hour = LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.HOURS)
        if (hour != openHour) rotate(hour, now)
        if (openHourBytes >= maxHourBytes) return@synchronized
        val entry = if (openHourBytes + line.length < maxHourBytes) line + LINE_SEPARATOR else HOUR_FULL_MARKER
        writer?.run {
            write(entry)
            flush()
        }
        openHourBytes = if (entry == HOUR_FULL_MARKER) maxHourBytes else openHourBytes + entry.length
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

    /** Applies retention and the byte budget without writing: logs left by a capture that is now off still go. */
    fun trim(now: Instant = Instant.now()) = synchronized(lock) {
        prune(now, LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.HOURS))
    }

    private fun rotate(hour: LocalDateTime, now: Instant) {
        runCatching { writer?.close() }
        directory.mkdirs()
        val file = File(directory, FILE_PREFIX + FILE_HOUR.format(hour) + FILE_SUFFIX)
        writer = FileWriter(file, true)
        openHour = hour
        openHourBytes = file.length()
        prune(now, hour)
    }

    /** Drops hours past [retention], then the oldest hours until the closed ones leave room for a full open hour. */
    private fun prune(now: Instant, openHour: LocalDateTime) {
        val oldestKept = LocalDateTime.ofInstant(now.minus(retention), zone).truncatedTo(ChronoUnit.HOURS)
        val (expired, kept) = logFiles().filter { (hour, _) -> hour != openHour }.partition { (hour, _) -> hour.isBefore(oldestKept) }
        expired.forEach { (_, file) -> file.delete() }
        var closedBytes = kept.sumOf { (_, file) -> file.length() }
        val closedBudget = maxTotalBytes - maxHourBytes
        kept.asSequence().takeWhile { closedBytes > closedBudget }.forEach { (_, file) ->
            closedBytes -= file.length()
            file.delete()
        }
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
        const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val DEFAULT_MAX_HOUR_BYTES = 8L * 1024 * 1024
        private const val HOUR_FULL_MARKER = "--- hourly log budget reached, dropping lines until the next hour ---\n"
        private const val FILE_PREFIX = "app-"
        private const val FILE_SUFFIX = ".log"
        private const val LINE_SEPARATOR = "\n"
        private val FILE_HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HH")

        // logcat `-v year` prefix, e.g. "2026-10-26 14:03:22.123".
        private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private const val LINE_TIME_LENGTH = 23
    }
}
