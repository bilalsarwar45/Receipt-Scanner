plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":domain:expenses"))
    api(project(":core:money-time"))
    testImplementation(libs.kotlinx.coroutines.test)
}
