plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.pohunek.companion.terminal"
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        // libghostty-vt is built for these ABIs only (scripts/build-libghostty-vt); x86_64 serves emulators.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
