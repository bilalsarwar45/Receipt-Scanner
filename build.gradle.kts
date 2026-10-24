buildscript {
    val jvmOnly = (findProperty("jvmOnly") as String?).toBoolean()
    repositories {
        if (!jvmOnly) google()
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        // Loading every plugin from one classpath keeps AGP and KGP in the same classloader.
        if (!jvmOnly) classpath(libs.android.gradle.plugin)
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.compose.gradle.plugin)
        classpath(libs.kotlin.serialization.gradle.plugin)
        if (!jvmOnly) classpath(libs.ksp.gradle.plugin)
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                allWarningsAsErrors.set(false)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        dependencies {
            add("testImplementation", libs_junit())
            add("testImplementation", libs_truth())
        }
        tasks.withType<Test>().configureEach {
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}

fun libs_junit() = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary("junit").get()
fun libs_truth() = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary("truth").get()
