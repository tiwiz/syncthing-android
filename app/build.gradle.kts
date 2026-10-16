import java.util.Properties

plugins {
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
}

dependencies {
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.aboutlibraries.core)
    implementation(libs.accompanist.permissions)
    implementation(libs.activity.compose)
    implementation(libs.activity.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.hilt.android)
    implementation(libs.documentfile)
    implementation(libs.fragment.ktx)
    implementation(libs.gson)
    implementation(libs.guava)
    implementation(libs.jbcrypt)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.lingala.zip4j)
    implementation(libs.localbroadcastmanager)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.stream)
    implementation(libs.volley)
    implementation(libs.webkit)
    implementation(libs.zhanghai.compose.preference)
    implementation(libs.zxing.android.embedded) { isTransitive = false }
    implementation(libs.zxing.core)
    ksp(libs.hilt.compiler)

    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    // Volley still references org.apache.http, which Android ships but Robolectric's image lacks.
    testImplementation(libs.httpclient)
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    // Instrumented tests (app/src/androidTest): the WebView screens with a real WebView.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.intents)
    androidTestImplementation(libs.espresso.web)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}

val goldenRecord = providers.gradleProperty("golden.record").getOrElse("false")
// -Pcoverage=true instruments debug unit tests for createDebugUnitTestCoverageReport. Off by
// default so the gate runs the same bytecode as the app.
val unitTestCoverage = providers.gradleProperty("coverage").getOrElse("false").toBoolean()

android {
    compileSdk = libs.versions.compile.sdk.get().toInt()
    namespace = "com.nutomic.syncthingandroid"
    ndkVersion = libs.versions.ndk.version.get()

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/libSyncthingNative.mk")
        }
    }

    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "com.github.catfriend1.syncthingfork"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = libs.versions.version.code.get().toInt()
        versionName = libs.versions.version.name.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val localProps = Properties()
            val localFile = rootProject.file("local.properties")
            if (localFile.exists()) {
                localFile.inputStream().use { localProps.load(it) }
            }
            
            fun propOrEnv(key: String): String? =
                localProps.getProperty(key) ?: System.getenv(key)
            
            storeFile = propOrEnv("SYNCTHING_RELEASE_STORE_FILE")?.let(::file)
            storePassword = propOrEnv("SIGNING_PASSWORD")
            keyAlias = propOrEnv("SYNCTHING_RELEASE_KEY_ALIAS")
            keyPassword = storePassword
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            // Debug APKs are signed by hand (build.cmd); instrumented tests need an installable
            // APK, so they use the SDK's debug key.
            signingConfig =
                signingConfigs.getByName("debug").takeIf {
                    project.gradle.startParameter.taskNames.any { name ->
                        name.contains("AndroidTest", ignoreCase = true)
                    }
                }
            enableUnitTestCoverage = unitTestCoverage
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.runCatching { getByName("release") }
                .getOrNull()
                .takeIf { it?.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            // Material 3 Expressive is still experimental; opted in once for the whole module
            // (docs/plans/M3-EXPRESSIVE.md).
            optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
            optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        }
    }

    bundle {
        language {
            enableSplit = false
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }

    splits {
        abi {
            // Only enable splits for release builds
            isEnable = project.gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Otherwise libsyncthing.so doesn't appear where it should in installs
            // based on app bundles, and thus nothing works.
            useLegacyPackaging = true
        }
    }

    sourceSets {
        // The instrumented tests share the anonymised Syncthing fixtures with the unit tests.
        getByName("androidTest").resources.directories.add("src/test/resources/fixtures")
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest.
            isIncludeAndroidResources = true
            all {
                // Robolectric's SDK 36 image reaches into JDK internals on JDK 21.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
                // -Pgolden.record=true rewrites the golden files of characterization tests.
                it.systemProperty("golden.record", goldenRecord)
                // Draw Compose with the hardware renderer so screenshots match the device.
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                if (unitTestCoverage) {
                    // Robolectric loads app classes through its own class loader.
                    it.extensions.configure<JacocoTaskExtension> {
                        isIncludeNoLocationClasses = true
                        excludes = listOf("jdk.internal.*")
                    }
                }
            }
        }
    }

    lint {
        abortOnError = true
        targetSdk = libs.versions.target.sdk.get().toInt()
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

}

roborazzi {
    // Golden screenshots are committed next to the tests.
    outputDir.set(file("src/test/screenshots"))
}

detekt {
    buildUponDefaultConfig.set(true)
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    basePath.set(rootProject.layout.projectDirectory)
    parallel.set(true)
}

ktfmt {
    kotlinLangStyle()
}

// Kotlin files in app/src/main that predate ktfmt. A file leaves the list when it is first
// refactored; every other Kotlin file, and every new one, must be ktfmt-clean.
val ktfmtLegacyFiles: List<String> =
    rootProject.file("config/ktfmt/legacy-files.txt").readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
    // Patterns are relative to the source root (app/src/main/java).
    exclude(ktfmtLegacyFiles.map { it.removePrefix("app/src/main/java/") })
}

// The type-resolved detekt tasks run more rules than the plain one: "debug" covers app/src/main,
// "debugUnitTest" covers app/src/test, "debugAndroidTest" covers app/src/androidTest. Plain
// `detekt` (which `check` depends on) becomes an alias for the three, so there is a single set of
// findings.
tasks.named("detekt") {
    enabled = false
    dependsOn("detektDebug", "detektDebugUnitTest", "detektDebugAndroidTest")
}

// detekt analyses the Kotlin sources only. Without the javac output on its classpath, every
// reference from Kotlin to the app's Java classes is unresolved and type-resolved rules go blind.
// The detekt plugin sets the task's classpath from the variant, after this script runs.
afterEvaluate {
    tasks.named<dev.detekt.gradle.Detekt>("detektDebug") {
        classpath.from(
            tasks.named<JavaCompile>("compileDebugJavaWithJavac").flatMap { it.destinationDirectory }
        )
    }
}

// Build scripts are shared with upstream; formatting them would only create merge conflicts.
tasks.matching { it.name == "ktfmtCheckScripts" || it.name == "ktfmtFormatScripts" }.configureEach {
    enabled = false
}

abstract class ValidateAppVersionCode : DefaultTask() {

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @TaskAction
    fun validate() {
        val name = versionName.get()
        val code = versionCode.get()

        val parts = name.split(".")
        if (parts.size != 4) {
            throw GradleException("Invalid versionName format: '$name'. Expected format 'major.minor.patch.wrapper'.")
        }

        val calculatedCode = parts[0].toInt() * 1_000_000 +
                             parts[1].toInt() * 10_000 +
                             parts[2].toInt() * 100 +
                             parts[3].toInt()

        if (calculatedCode != code) {
            throw GradleException("Version mismatch: Calculated versionCode ($calculatedCode) does not match declared versionCode ($code). Please review 'gradle/libs.versions.toml'.")
        }
    }
}

tasks.register<ValidateAppVersionCode>("validateAppVersionCode") {
    versionName.set(libs.versions.version.name)
    versionCode.set(libs.versions.version.code.map { it.toInt() })
}

project.afterEvaluate {
    tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }.configureEach {
        dependsOn("validateAppVersionCode")
    }

    val isCopilot = System.getenv("IS_COPILOT")?.toBoolean() ?: false
    if (!isCopilot) {
        tasks
            .matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
            .configureEach {
                dependsOn(":syncthing:buildNative")
            }
    }
}
