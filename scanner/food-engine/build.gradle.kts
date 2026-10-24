plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":scanner:nutrition-engine"))
    implementation(libs.kotlinx.serialization.json)
}
