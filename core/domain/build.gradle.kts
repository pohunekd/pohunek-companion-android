plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(project(":protocol"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
