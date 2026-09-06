import java.util.Properties

// Machine-local configuration. `local.properties` is gitignored: nothing here reaches the
// repository. Missing values fall back to "", which disables the announcement feed quietly
// (see AnnouncementFeedClient.feedUrl) instead of shipping a half-configured build.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String): String = localProperties.getProperty(name)?.trim().orEmpty()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ScienceFiction.TokenWatchAndroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ScienceFiction.TokenWatchAndroid"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Announcement feed (Firestore REST, no Firebase SDK). The key is an Android client
        // identifier restricted to the Firestore API and the feed document is public by design,
        // but it is still an account-scoped credential, so it lives in local.properties rather
        // than in the repository. Set ANNOUNCEMENT_PROJECT_ID / ANNOUNCEMENT_API_KEY /
        // ANNOUNCEMENT_FEED_DOC there; leaving them unset disables announcements.
        buildConfigField("String", "ANNOUNCEMENT_PROJECT_ID", "\"${localProperty("ANNOUNCEMENT_PROJECT_ID")}\"")
        buildConfigField("String", "ANNOUNCEMENT_API_KEY", "\"${localProperty("ANNOUNCEMENT_API_KEY")}\"")
        buildConfigField("String", "ANNOUNCEMENT_FEED_DOC", "\"${localProperty("ANNOUNCEMENT_FEED_DOC")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
