import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

val compileSdkVersion = libs.versions.compileSdk.get().toInt()
val minSdkVersion = libs.versions.minSdk.get().toInt()
val buildToolsVersionName = libs.versions.buildTools.get()
val jvmTargetVersion = libs.versions.jvmTarget.get()

/** Required `key = value` setting of config/dev.toml; a missing or invalid value fails the build. */
fun devConfigInt(key: String): Int {
    val line = rootProject.file("config/dev.toml").readLines().firstOrNull { it.trim().startsWith("$key ") || it.trim().startsWith("$key=") }
        ?: throw GradleException("config/dev.toml: missing required key '$key'")
    val value = line.substringAfter('=').trim().toIntOrNull()
    if (value == null || value <= 0) throw GradleException("config/dev.toml: '$key' must be a positive integer, got '${line.substringAfter('=').trim()}'")
    return value
}

val testForks = devConfigInt("test_forks")

subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = compileSdkVersion
            buildToolsVersion = buildToolsVersionName
            defaultConfig { minSdk = minSdkVersion }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
                targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
            }
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = compileSdkVersion
            buildToolsVersion = buildToolsVersionName
            defaultConfig { minSdk = minSdkVersion }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
                targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
            }
        }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
            targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions { jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion)) }
        }
    }
    tasks.withType<Test>().configureEach { maxParallelForks = testForks }
}

// One entry point for the unit-test gate: the debug unit tests of Android modules and `test` of JVM modules.
val unitTest = tasks.register("unitTest") {
    group = "verification"
    description = "Runs the unit tests of every module."
}
subprojects {
    val modulePath = path
    pluginManager.withPlugin("com.android.application") { unitTest.configure { dependsOn("$modulePath:testDebugUnitTest") } }
    pluginManager.withPlugin("com.android.library") { unitTest.configure { dependsOn("$modulePath:testDebugUnitTest") } }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { unitTest.configure { dependsOn("$modulePath:test") } }
}

// Formatting rules live in .editorconfig.
spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
    source.setFrom(
        fileTree(rootDir) {
            include("**/src/**/*.kt", "**/*.gradle.kts")
            exclude("**/build/**")
        },
    )
}
