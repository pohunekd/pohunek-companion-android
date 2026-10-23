plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Copies the repository's THIRD_PARTY_NOTICES.md into the assets so the About screen shows the one copy there is. */
abstract class CopyNoticesTask : DefaultTask() {
    @get:InputFile
    abstract val notices: RegularFileProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun copy() {
        notices.get().asFile.copyTo(assetsDir.get().file(notices.get().asFile.name).asFile, overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val copyNotices = tasks.register<CopyNoticesTask>("copy${variant.name.replaceFirstChar { it.uppercase() }}Notices") {
            notices.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyNotices, CopyNoticesTask::assetsDir)
    }
}

android {
    namespace = "dev.pohunek.companion"

    defaultConfig {
        applicationId = "dev.pohunek.companion"
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:mvi"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":terminal"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.android)
}
