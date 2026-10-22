plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.pohunek.companion.core.data"
}

dependencies {
    api(project(":core:domain"))
    api(project(":protocol"))
    api(project(":terminal"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
}
