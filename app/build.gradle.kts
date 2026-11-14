plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val hasKey = file("searchplus.p12").exists()

android {
    namespace = "com.chdv.searchplus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chdv.searchplus"
        minSdk = 26
        targetSdk = 30
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasKey) {
            create("fixed") {
                storeFile = file("searchplus.p12")
                storeType = "pkcs12"
                storePassword = "searchplus123"
                keyAlias = "searchplus"
                keyPassword = "searchplus123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("fixed") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // PDF text extraction (including the position of every letter, for highlighting)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device AI model for search by meaning
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    // OCR for scanned pages that have no text layer
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
}
