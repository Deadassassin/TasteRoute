import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Keys live in local.properties (gitignored). gradle.properties / -P overrides also work.
val secrets = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
}

fun secret(name: String, default: String = ""): String =
    (secrets.getProperty(name) ?: project.findProperty(name) as? String)?.trim().orEmpty().ifBlank { default }

android {
    namespace = "space.gexemy.tasteroute"
    compileSdk = 37

    defaultConfig {
        // Reverse-DNS of tasteroute.gexemy.space, the host already serving this app's API.
        // Google Play hard-rejects anything under com.example, so this can never go back.
        applicationId = "space.gexemy.tasteroute"
        minSdk = 23
        targetSdk = 37
        // 0.1.x until the first store build. Nothing has shipped, and a versionName of 1.0.0
        // on a tree that is still landing patches is a claim about maturity, not a number.
        versionCode = 20
        versionName = "0.1.20"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "GEXEMY_BASE_URL", "\"${secret("GEXEMY_BASE_URL")}\"")
        buildConfigField("String", "NIM_API_KEY", "\"${secret("NIM_API_KEY")}\"")
        buildConfigField("String", "NIM_BASE_URL", "\"${secret("NIM_BASE_URL", "https://integrate.api.nvidia.com/v1")}\"")
        // NIM_MODEL pins one model and skips probing. Leave it empty and NIM_MODELS is raced.
        // NIM_MODELS is now only the FALLBACK order. The server's /v1/ai/models is asked at every
        // open and wins, because a list compiled into an APK cannot know a model was retired after
        // that APK was built - which is exactly what happened to two of the four below on
        // 2026-08-25. Every id here was checked against build.nvidia.com on 2026-08-27.
        buildConfigField("String", "NIM_MODEL", "\"${secret("NIM_MODEL")}\"")
        buildConfigField(
            "String",
            "NIM_MODELS",
            "\"${secret("NIM_MODELS", "meta/llama-3.2-3b-instruct,google/gemma-3-4b-it,nv-mistralai/mistral-nemo-12b-instruct,google/gemma-3-12b-it,openai/gpt-oss-20b")}\"",
        )
    }

    buildTypes {
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // material-icons-extended and osmdroid both ship far more than this app uses; without
            // shrinking, the release APK is roughly double what a low-end phone needs to download.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.osmdroid.android)
    implementation(libs.play.services.location)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
