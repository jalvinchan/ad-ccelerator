import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "lab.youtube.accelerator"
    compileSdk = 37
    defaultConfig {
        applicationId = "lab.youtube.accelerator"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
        fileSystem.sync { from(scripts); into(outputDirectory) }
    }
}

val acceleratorAssets = tasks.register<AcceleratorAssets>("acceleratorAssets") {
    scripts.from(rootProject.file("src/ad-session.js"), rootProject.file("src/page-controller.js"))
    outputDirectory.set(layout.buildDirectory.dir("generated/acceleratorAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(acceleratorAssets, AcceleratorAssets::outputDirectory)
}

dependencies {
    testImplementation(libs.junit)
}
