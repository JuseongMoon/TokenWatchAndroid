import java.util.Properties
import org.gradle.api.GradleException

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

// Release signing. `keystore.properties` and the keystore itself are gitignored; both stay on the
// signing machine. When either is missing the release signing config is simply not created, so a
// clean clone still builds debug — but `assembleRelease`/`bundleRelease` fail loudly (see the
// readiness guard at the bottom) rather than quietly producing an unsigned artifact.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun keystoreProperty(name: String): String? =
    keystoreProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = keystoreProperty("storeFile")?.let { rootProject.file(it) }
val releaseStorePassword = keystoreProperty("storePassword")
val releaseKeyAlias = keystoreProperty("keyAlias")
val releaseKeyPassword = keystoreProperty("keyPassword")
val hasReleaseSigningConfig = releaseStoreFile?.exists() == true &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

// The announcement feed degrades silently by design: an empty key disables it instead of crashing.
// That is right for debug and wrong for a store build, so the guard below turns it into an error.
val announcementKeys = listOf("ANNOUNCEMENT_PROJECT_ID", "ANNOUNCEMENT_API_KEY", "ANNOUNCEMENT_FEED_DOC")
val missingAnnouncementKeys = announcementKeys.filter { localProperty(it).isEmpty() }

val releaseReadinessErrorMessage: String?
    get() = listOfNotNull(
        (
            "Release signing is not configured. Put storeFile / storePassword / keyAlias / " +
                "keyPassword in keystore.properties and make sure the keystore exists."
            ).takeUnless { hasReleaseSigningConfig },
        (
            "Announcement feed is not configured: ${missingAnnouncementKeys.joinToString()} " +
                "missing from local.properties. Releasing without it ships announcements " +
                "silently disabled."
            ).takeIf { missingAnnouncementKeys.isNotEmpty() },
    ).takeIf { it.isNotEmpty() }?.joinToString("\n")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.ScienceFiction.TokenWatchAndroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ScienceFiction.TokenWatchAndroid"
        minSdk = 28
        targetSdk = 36
        versionCode = 14
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Announcement feed (Firestore REST, no Firebase SDK). The key is an Android client
        // identifier restricted to the Firestore API and the feed document is public by design,
        // but it is still an account-scoped credential, so it lives in local.properties rather
        // than in the repository. Set ANNOUNCEMENT_PROJECT_ID / ANNOUNCEMENT_API_KEY /
        // ANNOUNCEMENT_FEED_DOC there; leaving them unset disables announcements.
        buildConfigField("String", "ANNOUNCEMENT_PROJECT_ID", "\"${localProperty("ANNOUNCEMENT_PROJECT_ID")}\"")
        buildConfigField("String", "ANNOUNCEMENT_API_KEY", "\"${localProperty("ANNOUNCEMENT_API_KEY")}\"")
        buildConfigField("String", "ANNOUNCEMENT_FEED_DOC", "\"${localProperty("ANNOUNCEMENT_FEED_DOC")}\"")

        // Analytics stays off in debug builds so development does not pollute production stats.
        // Set ANALYTICS_DEBUG=true in local.properties when verifying events in Firebase DebugView.
        buildConfigField(
            "boolean",
            "ANALYTICS_DEBUG",
            localProperty("ANALYTICS_DEBUG").ifEmpty { "false" },
        )
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // findByName, not getByName: absent on machines without the keystore, where the
            // readiness guard is what stops the build.
            signingConfig = signingConfigs.findByName("release")
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

// Pins the JDK that compiles the project, so a different JDK on PATH cannot change the output.
// Note this does NOT control the JVM that runs Gradle itself: this machine's default JDK is
// Temurin 26, which Gradle 8.13 cannot parse, so release builds must set JAVA_HOME to JBR 21
// (see CLAUDE.md).
kotlin {
    jvmToolchain(21)
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
    implementation(libs.androidx.browser)
    implementation(libs.play.review)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

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

val validateReleaseReadiness by tasks.registering {
    doLast {
        releaseReadinessErrorMessage?.let { throw GradleException(it) }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(validateReleaseReadiness) }

// Fails before any work starts, so a misconfigured machine finds out immediately.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.name == "assembleRelease" || it.name == "bundleRelease" }) {
        releaseReadinessErrorMessage?.let { throw GradleException(it) }
    }
}
