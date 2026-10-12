package ayx.whatsapp.group

import android.content.Context
import android.util.Log

/**
 * AyX JSON Configuration Loader (Firebase-style).
 *
 * Loads the packaged ayx-services.json configuration that was validated
 * and processed by the Gradle build task. The build generates
 * res/values/ayx_config.xml from app/ayx-services.json; this loader
 * reads those resources at runtime.
 *
 * Architecture: APK → ayx-services.json → Gradle task → generated
 * resources → AyxConfig → networking runtime → IMAYX API
 *
 * Thread-safe singleton. Configuration is loaded once and cached.
 * Never logs secrets. All values have safe defaults.
 */
object AyxConfig {
    private const val TAG = "AyxConfig"

    @Volatile
    private var loaded = false

    // Project identity
    var projectId: String = "ayx-whatsapp"; private set
    var applicationId: String = "ayx.whatsapp"; private set
    var projectName: String = "AyX WhatsApp"; private set

    // API endpoints
    var apiBaseUrl: String = "http://127.0.0.1:3101"; private set
    var publicApiBaseUrl: String = "https://api.imayx.in"; private set

    // Configuration
    var schemaVersion: Int = 1; private set
    var configVersion: Int = 1; private set
    var remoteConfigEnabled: Boolean = true; private set
    var appVerificationEnabled: Boolean = false; private set
    var connectionTimeoutMs: Int = 15_000; private set
    var readTimeoutMs: Int = 15_000; private set
    var maxRetryAttempts: Int = 4; private set
    var retryBackoffMs: LongArray = longArrayOf(2000L, 4000L, 8000L); private set

    // Feature flags
    var featureGroupApi: Boolean = true; private set
    var featureGroupSync: Boolean = true; private set
    var featureAnnouncements: Boolean = true; private set
    var featureStatuses: Boolean = true; private set
    var featureUserMessages: Boolean = true; private set
    var featureAdminBootstrap: Boolean = true; private set
    var featureHealthStatus: Boolean = true; private set
    var featureRemoteSettings: Boolean = false; private set

    // Sync policy
    var syncForegroundDebounceMs: Long = 300_000L; private set
    var syncWorkerIntervalMinutes: Long = 15L; private set
    var mediaCacheMaxBytes: Long = 25L * 1024 * 1024; private set

    /**
     * Load configuration from generated resources. Safe to call multiple
     * times; subsequent calls are no-ops. Must be called before accessing
     * any configuration values (call from Application.onCreate).
     */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        try {
            val res = ctx.resources
            val pkg = ctx.packageName

            fun str(name: String, default: String): String {
                val id = res.getIdentifier(name, "string", pkg)
                return if (id != 0) res.getString(id) else default
            }
            fun bool(name: String, default: Boolean): Boolean {
                val id = res.getIdentifier(name, "bool", pkg)
                return if (id != 0) res.getBoolean(id) else default
            }
            fun int(name: String, default: Int): Int {
                val id = res.getIdentifier(name, "integer", pkg)
                return if (id != 0) res.getInteger(id) else default
            }

            projectId = str("ayx_project_id", projectId)
            applicationId = str("ayx_application_id", applicationId)
            projectName = str("ayx_project_name", projectName)
            apiBaseUrl = str("ayx_api_base_url", apiBaseUrl).trimEnd('/')
            publicApiBaseUrl = str("ayx_public_api_base_url", publicApiBaseUrl).trimEnd('/')

            schemaVersion = int("ayx_schema_version", schemaVersion)
            configVersion = int("ayx_config_version", configVersion)
            remoteConfigEnabled = bool("ayx_remote_config_enabled", remoteConfigEnabled)
            appVerificationEnabled = bool("ayx_app_verification_enabled", appVerificationEnabled)
            connectionTimeoutMs = int("ayx_connection_timeout_sec", 15) * 1000
            readTimeoutMs = int("ayx_read_timeout_sec", 15) * 1000
            maxRetryAttempts = int("ayx_max_retry_attempts", maxRetryAttempts)

            featureGroupApi = bool("ayx_feature_group_api", featureGroupApi)
            featureGroupSync = bool("ayx_feature_group_sync", featureGroupSync)
            featureAnnouncements = bool("ayx_feature_announcements", featureAnnouncements)
            featureStatuses = bool("ayx_feature_statuses", featureStatuses)
            featureUserMessages = bool("ayx_feature_user_messages", featureUserMessages)
            featureAdminBootstrap = bool("ayx_feature_admin_bootstrap", featureAdminBootstrap)
            featureHealthStatus = bool("ayx_feature_health_status", featureHealthStatus)
            featureRemoteSettings = bool("ayx_feature_remote_settings", featureRemoteSettings)

            syncForegroundDebounceMs = int("ayx_sync_foreground_debounce_ms", 300_000)
                .toLong().coerceAtLeast(60_000L)
            syncWorkerIntervalMinutes = int("ayx_sync_worker_interval_min", 15)
                .toLong().coerceAtLeast(15L)
            mediaCacheMaxBytes = int("ayx_media_cache_max_mb", 25).toLong() * 1024 * 1024

            // Validate critical values
            if (apiBaseUrl.isBlank()) {
                Log.w(TAG, "api_base_url is blank, using default")
                apiBaseUrl = "http://127.0.0.1:3101"
            }
            if (!apiBaseUrl.startsWith("http://") && !apiBaseUrl.startsWith("https://")) {
                Log.w(TAG, "api_base_url missing scheme, using default")
                apiBaseUrl = "http://127.0.0.1:3101"
            }

            loaded = true
            Log.i(TAG, "Configuration loaded: project=$projectId schema=$schemaVersion api=$apiBaseUrl")
        } catch (e: Exception) {
            // Never crash on config load; defaults remain active.
            Log.e(TAG, "Failed to load configuration, using defaults: ${e.message}")
            loaded = true
        }
    }

    /** True if load() has been called (successfully or with fallback to defaults). */
    fun isLoaded(): Boolean = loaded

    /** Full URL for a backend API path. */
    fun apiUrl(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return apiBaseUrl + p
    }

    /** Full URL for a public (tunnel) API path. */
    fun publicApiUrl(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return publicApiBaseUrl + p
    }
}
