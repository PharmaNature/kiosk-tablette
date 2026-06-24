import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "fr.pharmanature.kiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.pharmanature.kiosk"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Clé de signature FIXE (committée) : toutes les versions partagent la même
    // signature, donc `adb install -r` met à jour SANS désinstaller — le Device
    // Owner et la config sont conservés. Fini les "signatures do not match".
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("signing/kiosk.p12")
            storePassword = "pharmanature"
            keyAlias = "kiosk"
            keyPassword = "pharmanature"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            // Build debug = adb reste utilisable (recovery) ; signé avec la clé fixe.
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
}
