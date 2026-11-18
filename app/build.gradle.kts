plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ktlint)
}

/** Reads a build setting from -P or gradle.properties; fails the build if it is missing. */
fun setting(name: String): String =
    providers.gradleProperty(name).orNull ?: error("Missing Gradle property '$name' (see android/gradle.properties)")

/**
 * Deliberately opt-in. This flag must only be used to build the one-device recovery APK;
 * normal builds keep the standard authenticated-release behaviour.
 */
val emergencyResetOnUnauthorized = providers.gradleProperty("qist.emergencyResetOnUnauthorized")
    .map { it.toBooleanStrict() }
    .getOrElse(false)

if (emergencyResetOnUnauthorized) {
    require(providers.gradleProperty("qist.signing.storeFile").isPresent) {
        "Emergency recovery APK must be signed with the existing Qist release keystore."
    }
}

android {
    namespace = "pk.qist.device"
    compileSdk = 37

    // Qist supports English and Urdu; avoid bundling unused library translations in the first-install APK.
    androidResources {
        localeFilters += setOf("en", "ur")
    }

    defaultConfig {
        applicationId = "pk.qist.device"
        // QR provisioning of a Device Owner needs Android 7.0.
        minSdk = 24
        targetSdk = 37
        // Emergency APK must update the currently published 0.2.2 (versionCode 4) installation.
        versionCode = 5
        versionName = "0.2.3-emergency"
        buildConfigField("boolean", "EMERGENCY_RESET_ON_UNAUTHORIZED", emergencyResetOnUnauthorized.toString())
        val updateUrl = setting("qist.lockerUpdateApkUrl")
        require(updateUrl.startsWith("https://")) { "qist.lockerUpdateApkUrl must use https" }
        buildConfigField("String", "LOCKER_UPDATE_APK_URL", "\"$updateUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("ciRelease") {
            val storePath = providers.gradleProperty("qist.signing.storeFile").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("qist.signing.storePassword").orNull
                keyAlias = providers.gradleProperty("qist.signing.keyAlias").orNull
                keyPassword = providers.gradleProperty("qist.signing.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_URL", "\"${setting("qist.apiUrl.debug")}\"")
            // On the emulator adb must keep working, so debug builds leave USB debugging alone.
            buildConfigField("boolean", "BLOCK_USB_DEBUGGING", "false")
        }
        release {
            if (providers.gradleProperty("qist.signing.storeFile").isPresent) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
            val releaseUrl = setting("qist.apiUrl.release")
            require(releaseUrl.startsWith("https://")) { "qist.apiUrl.release must use https" }
            buildConfigField("String", "API_URL", "\"$releaseUrl\"")
            buildConfigField("boolean", "BLOCK_USB_DEBUGGING", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // New library versions appear weekly; upgrades are a deliberate step, not a build failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reaches into java.io internals; newer JDKs only allow that when opened explicitly.
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED"
            )
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

ktlint {
    version = libs.versions.ktlint
    android = true
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.work.runtime)
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.profileinstaller)
    implementation(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.work.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)

    ktlintRuleset(libs.compose.rules.ktlint)
}
