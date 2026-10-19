import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "lab.youtube.accelerator"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "lab.youtube.accelerator"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.6.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Ship only the engine for the device's CPU, rather than all three in one APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

// Package the tested source directly; do not maintain an Android script fork.
abstract class AcceleratorAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scripts: ConfigurableFileCollection
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun generate() {
        fileSystem.sync { from(scripts); into(outputDirectory.dir("accelerator")) }
    }
}

val acceleratorAssets = tasks.register<AcceleratorAssets>("acceleratorAssets") {
    scripts.from(rootProject.file("src/ad-session.js"), rootProject.file("src/page-controller.js"),
        rootProject.file("src/gecko-host.js"), rootProject.file("src/player-controls.js"), rootProject.file("src/manifest.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/acceleratorAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(acceleratorAssets, AcceleratorAssets::outputDirectory)
}

dependencies {
    implementation(libs.geckoview)
    testImplementation(libs.junit)
}
