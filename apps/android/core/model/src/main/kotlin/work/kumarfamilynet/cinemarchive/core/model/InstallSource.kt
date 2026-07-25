package work.kumarfamilynet.cinemarchive.core.model

/** Where this build was installed from, which decides how it can be updated. */
enum class InstallSource {
    /** Installed by the Play Store — Play owns the update path. */
    PLAY_STORE,

    /** APK installed by hand (e.g. from a GitHub Release), or by any other installer. */
    SIDELOADED,

    /** The platform wouldn't say. Treated as [SIDELOADED] for update purposes, since the
     *  Play path can't work without Play having installed us. */
    UNKNOWN,
}
