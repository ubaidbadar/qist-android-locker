import java.io.File
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ktlint)
}

/** Parses .env files into key-value pairs (workspace root, project root, and app module). */
fun loadDotEnv(): Map<String, String> {
    val env = mutableMapOf<String, String>()
    val candidateFiles = listOf(
        rootProject.file("../.env"),
        rootProject.file(".env"),
        project.file(".env"),
        rootProject.file(".env.local"),
        project.file(".env.local")
    )
    for (candidate in candidateFiles) {
        if (candidate.isFile) {
            candidate.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
                    val parts = line.split("=", limit = 2)
                    val key = parts[0].trim()
                    var value = parts[1].trim()
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    env[key] = value
                }
            }
        }
    }
    return env
}

val dotEnv = loadDotEnv()

/** Looks up value across System.getenv, .env, and Gradle properties. */
fun resolveEnv(envKey: String, gradlePropName: String? = null): String? = System.getenv(envKey)
    ?: dotEnv[envKey]
    ?: (gradlePropName?.let { providers.gradleProperty(it).orNull })
    ?: providers.gradleProperty(envKey).orNull

/** Reads a build setting from env (process/dotenv), -P, or gradle.properties; fails the build if it is missing. */
fun setting(name: String, envKey: String? = null): String = (envKey?.let { resolveEnv(it, name) })
    ?: resolveEnv(name)
    ?: providers.gradleProperty(name).orNull
    ?: error("Missing Gradle property '$name' (see android/gradle.properties or .env)")

android {
    namespace = "pk.qist.device"
    compileSdk = 37

    defaultConfig {
        applicationId = "pk.qist.device"
        // QR provisioning of a Device Owner needs Android 7.0.
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("ciRelease") {
            val keystoreBase64 = resolveEnv("ANDROID_KEYSTORE_BASE64")
            val explicitStoreFile = resolveEnv("ANDROID_KEYSTORE_FILE", "qist.signing.storeFile")

            if (!keystoreBase64.isNullOrBlank()) {
                val decodedKeystore = layout.buildDirectory.file("keystore/release.jks").get().asFile
                if (!decodedKeystore.exists() || decodedKeystore.length() == 0L) {
                    decodedKeystore.parentFile.mkdirs()
                    decodedKeystore.writeBytes(Base64.getDecoder().decode(keystoreBase64.trim()))
                }
                storeFile = decodedKeystore
            } else if (!explicitStoreFile.isNullOrBlank()) {
                storeFile = file(explicitStoreFile)
            } else {
                val candidateKeystores = listOf(
                    rootProject.file("../qist-release.jks"),
                    rootProject.file("qist-release.jks"),
                    project.file("qist-release.jks")
                )
                val found = candidateKeystores.find { it.isFile }
                if (found != null) {
                    storeFile = found
                }
            }

            storePassword = resolveEnv("ANDROID_KEYSTORE_PASSWORD", "qist.signing.storePassword")
            keyAlias = resolveEnv("ANDROID_KEY_ALIAS", "qist.signing.keyAlias")
            keyPassword = resolveEnv("ANDROID_KEY_PASSWORD", "qist.signing.keyPassword")
        }
    }

    buildTypes {
        debug {
            val debugUrl = resolveEnv("API_URL_DEBUG", "qist.apiUrl.debug")
                ?: setting("qist.apiUrl.debug")
            buildConfigField("String", "API_URL", "\"$debugUrl\"")
            // On the emulator adb must keep working, so debug builds leave USB debugging alone.
            buildConfigField("boolean", "BLOCK_USB_DEBUGGING", "false")
        }
        release {
            val ciRelease = signingConfigs.getByName("ciRelease")
            if (ciRelease.storeFile?.isFile == true && !ciRelease.storePassword.isNullOrBlank()) {
                signingConfig = ciRelease
            }

            val releaseUrl = resolveEnv("API_URL", "qist.apiUrl.release")
                ?: setting("qist.apiUrl.release")
            require(releaseUrl.startsWith("https://")) { "qist.apiUrl.release or API_URL must use https" }
            buildConfigField("String", "API_URL", "\"$releaseUrl\"")
            buildConfigField("boolean", "BLOCK_USB_DEBUGGING", "true")

            val signatureChecksum = resolveEnv("DEVICE_APP_SIGNATURE_CHECKSUM")
            if (!signatureChecksum.isNullOrBlank()) {
                buildConfigField("String", "DEVICE_APP_SIGNATURE_CHECKSUM", "\"$signatureChecksum\"")
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
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

    ktlintRuleset(libs.compose.rules.ktlint)
}
