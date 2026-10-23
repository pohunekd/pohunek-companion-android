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

// Release signing comes from these environment variables only. None set: the release APK stays unsigned
// (never the debug key). Some but not all set: the build fails and names the missing ones.
val signingKeystoreFile = "ANDROID_RELEASE_KEYSTORE_FILE"
val signingVariables = listOf(
    signingKeystoreFile,
    "ANDROID_RELEASE_KEYSTORE_PASSWORD",
    "ANDROID_RELEASE_KEY_ALIAS",
    "ANDROID_RELEASE_KEY_PASSWORD",
)
val signing = signingVariables.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotEmpty) }
val missingSigning = signing.filterValues { it == null }.keys
if (missingSigning.isNotEmpty() && missingSigning.size < signingVariables.size) {
    throw GradleException("Release signing is partly configured; missing: ${missingSigning.joinToString()}")
}
val releaseSigned = missingSigning.isEmpty()
if (releaseSigned && !file(signing.getValue(signingKeystoreFile).orEmpty()).isFile) {
    throw GradleException("$signingKeystoreFile does not point to a file")
}

// -PreleaseTag=vX.Y.Z[-pre.N] derives the published version; without it a local build keeps the values below.
//   versionName = X.Y.Z[-pre.N]
//   versionCode = X * 10_000_000 + Y * 100_000 + Z * 1_000 + (N for -pre.N, else 999)
// so every pre-release of a version sorts below the version itself and any higher version sorts above both.
// Y and Z must be below 100, N from 1 to 998 and X at most 209, which keeps the code under Android's 2_100_000_000.
val releaseTagPattern = Regex("""v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-pre\.([1-9]\d*))?""")
val finalReleaseStage = 999L
val maxVersionCode = 2_100_000_000L
val releaseTag = providers.gradleProperty("releaseTag").orNull
val releaseMatch = releaseTag?.let {
    releaseTagPattern.matchEntire(it) ?: throw GradleException("releaseTag '$it' is not vX.Y.Z or vX.Y.Z-pre.N")
}
val releaseVersionName = releaseMatch?.value?.removePrefix("v")
val releaseVersionCode = releaseMatch?.run {
    val (major, minor, patch) = groupValues.subList(1, 4).map { it.toLong() }
    val preNumber = groupValues[4].toLongOrNull()
    val code = major * 10_000_000L + minor * 100_000L + patch * 1_000L + (preNumber ?: finalReleaseStage)
    val partsInRange = minor < 100 && patch < 100 && (preNumber == null || preNumber in 1 until finalReleaseStage)
    if (!partsInRange || code > maxVersionCode) {
        throw GradleException("releaseTag '$releaseTag' is outside the versionCode range (minor and patch below 100, pre number 1-998)")
    }
    code.toInt()
}
if (releaseTag != null && !releaseSigned) {
    throw GradleException("A tagged release must be signed; set ${signingVariables.joinToString()}")
}

android {
    namespace = "dev.pohunek.companion"

    defaultConfig {
        applicationId = "dev.pohunek.companion"
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.1.0"
    }

    signingConfigs {
        if (releaseSigned) {
            create("release") {
                storeFile = file(signing.getValue(signingKeystoreFile).orEmpty())
                storePassword = signing["ANDROID_RELEASE_KEYSTORE_PASSWORD"]
                keyAlias = signing["ANDROID_RELEASE_KEY_ALIAS"]
                keyPassword = signing["ANDROID_RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigned) signingConfig = signingConfigs.getByName("release")
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
