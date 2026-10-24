plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":domain:expenses"))
    testImplementation(libs.kotlinx.coroutines.test)
}
