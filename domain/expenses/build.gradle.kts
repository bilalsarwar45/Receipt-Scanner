plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":core:money-time"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
