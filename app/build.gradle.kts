import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.time.Instant
import java.util.UUID

plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Execution-time metadata: configuration cache never captures a timestamp or UUID. */
@DisableCachingByDefault(because = "Every APK build must carry a fresh identity")
abstract class GenerateBuildMetadata : DefaultTask() {
    @get:Input abstract val buildTypeName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Build identities must not be restored from the build cache") { true }
    }

    @TaskAction fun generate() {
        val directory = outputDirectory.get().asFile
        check(directory.isDirectory || directory.mkdirs())
        directory.resolve("ontop_build.json").writeText(
            """{"buildId":"${UUID.randomUUID()}","builtAt":"${Instant.now()}","buildType":"${buildTypeName.get()}"}""",
            Charsets.UTF_8
        )
    }
}

androidComponents {
    onVariants { variant ->
        val metadata = tasks.register<GenerateBuildMetadata>("generate${variant.name.replaceFirstChar { it.uppercaseChar() }}BuildMetadata") {
            buildTypeName.set(variant.buildType ?: variant.name)
            outputDirectory.set(layout.buildDirectory.dir("generated/buildMetadata/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(metadata, GenerateBuildMetadata::outputDirectory)
    }
}

android {
    namespace = "com.ontop.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ontop.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // One shared engine for local audio, recording previews and local video.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation(libs.androidx.glance.appwidget)
    // Same WorkManager version already used transitively by Glance 1.1.1.
    implementation("androidx.work:work-runtime-ktx:2.7.1")
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
