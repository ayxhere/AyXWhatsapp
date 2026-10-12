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
            // R8 ON for release. If the release APK misbehaves (missing classes at
            // runtime), set this back to false and report — then tighten the keeps
            // in proguard-rules.pro instead of shipping unshrunk.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.security:security-crypto:1.0.0")       // EncryptedSharedPreferences for the AyX Group token
    implementation("androidx.work:work-runtime-ktx:2.9.0")          // periodic AyX updates sync (no Firebase in this app)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
