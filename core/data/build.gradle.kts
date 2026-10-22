plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.pohunek.companion.core.data"
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
}
