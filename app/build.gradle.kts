import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import java.time.Instant

plugins {
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

abstract class GenerateProjectLicense : DefaultTask() {
    @get:InputFile
    abstract val licenseFile: RegularFileProperty
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
    @TaskAction
    fun generate() {
        val output = outputDirectory.file("project_license.txt").get().asFile
        output.parentFile.mkdirs()
        licenseFile.get().asFile.copyTo(output, overwrite = true)
    }
}
val generateProjectLicense = tasks.register<GenerateProjectLicense>("generateProjectLicense") {
    licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
    outputDirectory.set(layout.buildDirectory.dir("generated/projectLicense/assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(generateProjectLicense) { it.outputDirectory }
}

android {
    namespace = "dev.uednd.hydros"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.uednd.hydros"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val buildTime = providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.toLongOrNull()
            ?.let(Instant::ofEpochSecond) ?: Instant.now()
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        val defaultBaseUrl = String(byteArrayOf(104, 116, 116, 112, 115, 58, 47, 47, 118, 51, 45, 97, 112, 105, 46, 99, 104, 105, 110, 97, 45, 113, 122, 120, 121, 46, 99, 110, 47), Charsets.UTF_8)
        buildConfigField("String", "CAMPUS_BASE_URL", "\"${providers.gradleProperty("campusBaseUrl").getOrElse(defaultBaseUrl)}\"")
        testInstrumentationRunner = "dev.uednd.hydros.OfflineTestRunner"
    }

    buildTypes {
        release {
            // AGP 9.3+ enables R8, optimized resource shrinking and Android keep rules together.
            optimization {
                enable = true
            }
        }
    }

    androidResources {
        // The app uses Chinese; retain English as the fallback for library UI.
        localeFilters += listOf("zh", "en")
    }

    splits {
        abi {
            // AGP cannot build a resource-shrunk AAB with ABI APK splits enabled.
            isEnable = providers.gradleProperty("splitApks").map(String::toBoolean).getOrElse(false)
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        }
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.aboutlibraries.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.mqtt.paho)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

