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
// Capture at configuration time — 'android' doesn't resolve inside doLast
val ayxExpectedAppId = android.defaultConfig.applicationId

fun ayxExtractString(jsonText: String, key: String, default: String): String {
    val m = Regex("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").find(jsonText)
    return m?.groupValues?.get(1) ?: default
}
fun ayxExtractBoolean(jsonText: String, key: String, default: Boolean): Boolean {
    val m = Regex("\"" + key + "\"\\s*:\\s*(true|false)").find(jsonText)
    return m?.groupValues?.get(1)?.toBoolean() ?: default
}
fun ayxExtractInt(jsonText: String, key: String, default: Int): Int {
    val m = Regex("\"" + key + "\"\\s*:\\s*(\\d+)").find(jsonText)
    return m?.groupValues?.get(1)?.toIntOrNull() ?: default
}

tasks.register("processAyxServicesJson") {
    description = "Validates app/ayx-services.json and generates ayx_config.xml resources"
    group = "ayx"

    inputs.file(ayxServicesJson)
    outputs.file(ayxConfigXml)

    doLast {
        if (!ayxServicesJson.exists()) {
            throw GradleException("Missing app/ayx-services.json — required for AyX configuration")
        }
        val text = ayxServicesJson.readText()

        val configAppId = ayxExtractString(text, "application_id", "")
        if (configAppId.isEmpty()) {
            throw GradleException("ayx-services.json: project_info.application_id is required")
        }
        if (configAppId != ayxExpectedAppId) {
            throw GradleException(
                "ayx-services.json application_id '" + configAppId + "' does not match " +
                "build applicationId '" + ayxExpectedAppId + "'"
            )
        }

        val apiUrl = ayxExtractString(text, "api_base_url", "")
        if (apiUrl.isEmpty()) {
            throw GradleException("ayx-services.json: project_info.api_base_url is required")
        }
        if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
            throw GradleException("ayx-services.json: api_base_url must start with http:// or https://")
        }

        ayxConfigXml.parentFile.mkdirs()
        val projectId = ayxExtractString(text, "project_id", "ayx-whatsapp")
        val projectName = ayxExtractString(text, "project_name", "AyX WhatsApp")
        val publicApiUrl = ayxExtractString(text, "public_api_base_url", "https://api.imayx.in")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<!-- Generated by processAyxServicesJson — DO NOT EDIT. Source: app/ayx-services.json -->\n")
        sb.append("<resources>\n")
        sb.append("    <string name=\"ayx_project_id\">" + projectId + "</string>\n")
        sb.append("    <string name=\"ayx_application_id\">" + configAppId + "</string>\n")
        sb.append("    <string name=\"ayx_project_name\">" + projectName + "</string>\n")
        sb.append("    <string name=\"ayx_api_base_url\">" + apiUrl + "</string>\n")
        sb.append("    <string name=\"ayx_public_api_base_url\">" + publicApiUrl + "</string>\n")
        sb.append("    <integer name=\"ayx_schema_version\">" + ayxExtractInt(text, "schema_version", 1) + "</integer>\n")
        sb.append("    <integer name=\"ayx_config_version\">" + ayxExtractInt(text, "config_version", 1) + "</integer>\n")
        sb.append("    <integer name=\"ayx_connection_timeout_sec\">" + ayxExtractInt(text, "connection_timeout_seconds", 15) + "</integer>\n")
        sb.append("    <integer name=\"ayx_read_timeout_sec\">" + ayxExtractInt(text, "read_timeout_seconds", 15) + "</integer>\n")
        sb.append("    <integer name=\"ayx_max_retry_attempts\">" + ayxExtractInt(text, "max_retry_attempts", 4) + "</integer>\n")
        sb.append("    <bool name=\"ayx_remote_config_enabled\">" + ayxExtractBoolean(text, "remote_config_enabled", true) + "</bool>\n")
        sb.append("    <bool name=\"ayx_app_verification_enabled\">" + ayxExtractBoolean(text, "app_verification_enabled", false) + "</bool>\n")
        sb.append("    <bool name=\"ayx_feature_group_api\">" + ayxExtractBoolean(text, "group_api", true) + "</bool>\n")
        sb.append("    <bool name=\"ayx_feature_group_sync\">" + ayxExtractBoolean(text, "group_sync", true) + "</bool>\n")
        sb.append("    <bool name=\"ayx_feature_announcements\">" + ayxExtractBoolean(text, "announcements", true) + "</bool>\n")
        sb.append("    <bool name=\"ayx_feature_statuses\">" + ayxExtractBoolean(text, "statuses", true) + "</bool>\n")
        sb.append("    <bool name=\"ayx_feature_user_messages\">" + ayxExtractBoolean(text, "user_messages", true) + "</bool>\n")
        sb.append("    <bool name=\"ayx_feature_admin_bootstrap\">" + ayxExtractBoolean(text, "admin_bootstrap", true) + "</bool>\n")
        sb.append("    <bool name=\"ayx_feature_health_status\">" + ayxExtractBoolean(text, "health_status", true) + "</bool>\n")
        sb.append("    <bool name=\"ayx_feature_remote_settings\">" + ayxExtractBoolean(text, "remote_settings", false) + "</bool>\n")
        sb.append("    <integer name=\"ayx_sync_foreground_debounce_ms\">" + ayxExtractInt(text, "foreground_debounce_ms", 300000) + "</integer>\n")
        sb.append("    <integer name=\"ayx_sync_worker_interval_min\">" + ayxExtractInt(text, "worker_interval_minutes", 15) + "</integer>\n")
        sb.append("    <integer name=\"ayx_media_cache_max_mb\">" + (ayxExtractInt(text, "media_cache_max_bytes", 26214400) / 1048576) + "</integer>\n")
        sb.append("</resources>\n")
        ayxConfigXml.writeText(sb.toString())
        println("AyX: Generated " + ayxConfigXml.path + " from ayx-services.json")
    }
}

// Hook into build lifecycle: process JSON before resources are merged
tasks.matching { it.name.startsWith("merge") && it.name.contains("Resources") }.configureEach {
    dependsOn("processAyxServicesJson")
}
