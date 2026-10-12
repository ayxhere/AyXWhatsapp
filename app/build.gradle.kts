plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ayx.whatsapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "ayx.whatsapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 123
        versionName = "6.63"

        ndk {
            // Ship arm64 only for the spike (covers essentially all modern phones).
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    val hasKeystore = System.getenv("KEYSTORE_FILE") != null
    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(System.getenv("KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true    // OK now: .so uncompressed + zip noCompress
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "26.3.11579264"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    androidResources {
        noCompress += "zip"   // keep assets/nodejs-project.zip STORED, not deflated
    }

    packaging {
        // Extract libnode.so to the filesystem (safer for the embedded runtime).
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.zxing:core:3.5.3")   // QR generation for Support Development
    implementation("androidx.emoji2:emoji2:1.5.0")   // consistent colour emoji (downloadable, no APK size)
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ===== AyX Services JSON Configuration (Firebase-style) =====
// Processes app/ayx-services.json → generates res/values/ayx_config.xml
// Validates: JSON syntax, required fields, applicationId match, URL format.

val ayxServicesJson = file("ayx-services.json")
val ayxConfigXml = file("src/main/res/values/ayx_config.xml")

tasks.register("processAyxServicesJson") {
    description = "Validates app/ayx-services.json and generates ayx_config.xml resources"
    group = "ayx"

    inputs.file(ayxServicesJson)
    outputs.file(ayxConfigXml)

    doLast {
        if (!ayxServicesJson.exists()) {
            throw GradleException("Missing app/ayx-services.json — required for AyX configuration")
        }
        val json = try {
            groovy.json.JsonSlurper().parseText(ayxServicesJson.readText()) as Map<*, *>
        } catch (e: Exception) {
            throw GradleException("Invalid JSON in app/ayx-services.json: ${e.message}")
        }

        // Validate required sections
        val projectInfo = json["project_info"] as? Map<*, *>
            ?: throw GradleException("ayx-services.json: missing 'project_info'")
        val configuration = json["configuration"] as? Map<*, *>
            ?: throw GradleException("ayx-services.json: missing 'configuration'")
        val features = json["features"] as? Map<*, *>
            ?: throw GradleException("ayx-services.json: missing 'features'")

        // Validate application_id matches
        val configAppId = projectInfo["application_id"] as? String
            ?: throw GradleException("ayx-services.json: project_info.application_id is required")
        val manifestAppId = android.defaultConfig.applicationId
        if (configAppId != manifestAppId) {
            throw GradleException(
                "ayx-services.json application_id '$configAppId' does not match " +
                "build applicationId '$manifestAppId'"
            )
        }

        // Validate API URL
        val apiUrl = projectInfo["api_base_url"] as? String
            ?: throw GradleException("ayx-services.json: project_info.api_base_url is required")
        if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
            throw GradleException("ayx-services.json: api_base_url must start with http:// or https://")
        }

        // Helper to safely extract values
        fun str(map: Map<*, *>, key: String, default: String): String =
            (map[key] as? String) ?: default
        fun bool(map: Map<*, *>, key: String, default: Boolean): Boolean =
            (map[key] as? Boolean) ?: default
        fun intVal(map: Map<*, *>, key: String, default: Int): Int =
            (map[key] as? Number)?.toInt() ?: default

        val sync = json["sync"] as? Map<*, *> ?: emptyMap<Any, Any>()

        // Generate XML resources
        ayxConfigXml.parentFile.mkdirs()
        ayxConfigXml.writeText("""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by processAyxServicesJson — DO NOT EDIT. Source: app/ayx-services.json -->
<resources>
    <string name="ayx_project_id">${str(projectInfo, "project_id", "ayx-whatsapp")}</string>
    <string name="ayx_application_id">$configAppId</string>
    <string name="ayx_project_name">${str(projectInfo, "project_name", "AyX WhatsApp")}</string>
    <string name="ayx_api_base_url">$apiUrl</string>
    <string name="ayx_public_api_base_url">${str(projectInfo, "public_api_base_url", "https://api.imayx.in")}</string>

    <integer name="ayx_schema_version">${intVal(configuration, "schema_version", 1)}</integer>
    <integer name="ayx_config_version">${intVal(configuration, "config_version", 1)}</integer>
    <integer name="ayx_connection_timeout_sec">${intVal(configuration, "connection_timeout_seconds", 15)}</integer>
    <integer name="ayx_read_timeout_sec">${intVal(configuration, "read_timeout_seconds", 15)}</integer>
    <integer name="ayx_max_retry_attempts">${intVal(configuration, "max_retry_attempts", 4)}</integer>

    <bool name="ayx_remote_config_enabled">${bool(configuration, "remote_config_enabled", true)}</bool>
    <bool name="ayx_app_verification_enabled">${bool(configuration, "app_verification_enabled", false)}</bool>

    <bool name="ayx_feature_group_api">${bool(features, "group_api", true)}</bool>
    <bool name="ayx_feature_group_sync">${bool(features, "group_sync", true)}</bool>
    <bool name="ayx_feature_announcements">${bool(features, "announcements", true)}</bool>
    <bool name="ayx_feature_statuses">${bool(features, "statuses", true)}</bool>
    <bool name="ayx_feature_user_messages">${bool(features, "user_messages", true)}</bool>
    <bool name="ayx_feature_admin_bootstrap">${bool(features, "admin_bootstrap", true)}</bool>
    <bool name="ayx_feature_health_status">${bool(features, "health_status", true)}</bool>
    <bool name="ayx_feature_remote_settings">${bool(features, "remote_settings", false)}</bool>

    <integer name="ayx_sync_foreground_debounce_ms">${intVal(sync, "foreground_debounce_ms", 300000)}</integer>
    <integer name="ayx_sync_worker_interval_min">${intVal(sync, "worker_interval_minutes", 15)}</integer>
    <integer name="ayx_media_cache_max_mb">${(intVal(sync, "media_cache_max_bytes", 26214400) / 1048576)}</integer>
</resources>
""")
        println("AyX: Generated ${ayxConfigXml.path} from ayx-services.json")
    }
}

// Hook into build lifecycle: process JSON before resources are merged
tasks.matching { it.name.startsWith("merge") && it.name.contains("Resources") }.configureEach {
    dependsOn("processAyxServicesJson")
}
