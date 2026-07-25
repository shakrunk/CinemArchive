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
 * Progress of a direct in-app APK install, tracked separately from [UpdateCheckResult] because
 * the two aren't alternatives: an install is happening *to* an already-found update, and
 * folding a failure into [UpdateCheckResult.Failed] would erase the
 * [UpdateCheckResult.Available] that the retry button depends on.
 *
 * [AwaitingConfirmation] is a real state, not a formality — `PackageInstaller` never installs
 * without the user accepting a system dialog, even with `REQUEST_INSTALL_PACKAGES` granted, so
 * the app hands the session over and waits.
 */
sealed interface ApkInstallState {
    data object Idle : ApkInstallState

    data object Downloading : ApkInstallState

    /** Handed to the system installer; its confirmation dialog is up. */
    data object AwaitingConfirmation : ApkInstallState

    /** Rarely observed — the system usually kills the app as it swaps the package out. */
    data object Installed : ApkInstallState

    data class Failed(val message: String) : ApkInstallState
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
