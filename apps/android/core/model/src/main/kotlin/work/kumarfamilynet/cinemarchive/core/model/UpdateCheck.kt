package work.kumarfamilynet.cinemarchive.core.model

/** Outcome of an update check, for the Settings screen to render inline. */
sealed interface UpdateCheckResult {
    /** No check has run yet this session. */
    data object Idle : UpdateCheckResult

    data object Checking : UpdateCheckResult

    data class UpToDate(val currentVersion: String) : UpdateCheckResult

    data class Available(
        val currentVersion: String,
        val latestVersion: String,
        /** Direct APK asset from the release, when it published one. */
        val apkUrl: String?,
        /** The release's own page, used as the fallback when we can't install directly. */
        val releasePageUrl: String,
    ) : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * Compares two `MAJOR.MINOR.PATCH` version strings, ignoring a leading `v` and any
 * pre-release/build suffix. Returns >0 when [a] is newer than [b], 0 when equal, <0 when older.
 *
 * Deliberately not `String.compareTo`: "1.10.0" must be newer than "1.9.0", and a plain
 * lexicographic compare gets that backwards.
 */
fun compareVersions(a: String, b: String): Int {
    val pa = parseVersion(a)
    val pb = parseVersion(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (d != 0) return d
    }
    return 0
}

private fun parseVersion(v: String): List<Int> =
    v.trim()
        .removePrefix("v")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
