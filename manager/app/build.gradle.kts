plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.xpatch"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.xpatch"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file("src/main/assets/xp/key.p12")
            storePassword = "12345678"
            keyAlias = "xpatch"
            keyPassword = "12345678"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.runtime:runtime:1.12.1")
    implementation("androidx.compose.animation:animation:1.12.1")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.android.tools.build:apksig:8.7.2")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4") {
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class")
    }
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4") {
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class")
    }
}

configurations.all {
    resolutionStrategy {
        force("androidx.compose.material3:material3:1.5.0-beta02")
        force("androidx.compose.material3:material3-window-size-class:1.5.0-beta02")
    }
}




