package com.example.cardvault

/**
 * Application-scoped holders for state that must survive the Activity being
 * destroyed — which happens routinely when a camera or photo-picker takes
 * over the screen (some OEMs also kill the process in that window).
 *
 * The session object holds the raw encryption key in memory only; it can
 * never be persisted. But it also should not be lost just because the
 * system rebuilt the Activity, so it lives here, on the process singleton,
 * instead of on MainActivity.
 */
object VaultRuntime {
    @Volatile var session: VaultStore.Session? = null
    @Volatile var section: String = "Overview"
}
