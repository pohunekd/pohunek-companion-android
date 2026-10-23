plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Hand-run integration checks (not part of the test suite); see the files under src/manual.
val manual = sourceSets.create("manual") {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Prints the classpath of the manual checks so they can run with a plain `java -cp` in another container.
tasks.register("manualClasspath") {
    group = "verification"
    description = "Compiles the manual checks and prints their runtime classpath."
    dependsOn(manual.classesTaskName)
    val classpath = manual.runtimeClasspath
    doLast { println("MANUAL_CLASSPATH=" + classpath.asPath) }
}
