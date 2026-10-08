import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val apiKeys = Properties().apply {
    val f = rootProject.file("keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.waypoint"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.waypoint"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "MAPS_KEY",
            "\"${apiKeys.getProperty("MAPS_API_KEY", "")}\""
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint {
        disable += listOf("MockLocation", "ProtectedPermissions")
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.koin.android)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.osmdroid)
    implementation(libs.lottie)
    implementation(libs.play.services.location)
    implementation(libs.core.splashscreen)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
