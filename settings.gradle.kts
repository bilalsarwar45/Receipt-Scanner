// Pass -PjvmOnly=true to build only the pure-Kotlin modules (no Android SDK or Google Maven needed).
val jvmOnly = providers.gradleProperty("jvmOnly").map { it.toBoolean() }.getOrElse(false)

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Mirror of Maven Central, listed first because Central rate-limits shared build machines.
        maven("https://plugins.gradle.org/m2/") { content { excludeGroupByRegex("androidx\\..*|com\\.android.*|com\\.google\\.(android|mlkit|mediapipe|gms).*") } }
        if (!(providers.gradleProperty("jvmOnly").orNull.toBoolean())) {
            google {
                content {
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\.(android|mlkit|mediapipe|gms|firebase|ai|testing|odml).*")
                }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "receipt-scan"

// Pure Kotlin modules: domain rules, parsers and engines. Built and tested on any JVM.
val jvmModules = listOf(
    ":core:money-time",
    ":core:scanner-contracts",
    ":core:ocr-model",
    ":core:model-packs-engine",
    ":domain:expenses",
    ":domain:receipt-actions",
    ":domain:insights",
    ":feature:commands-engine",
    ":scanner:receipt-engine",
    ":scanner:invoice-engine",
    ":scanner:qr-engine",
    ":scanner:text-engine",
    ":scanner:product-engine",
    ":scanner:nutrition-engine",
    ":scanner:expiry-engine",
    ":scanner:math-engine",
    ":scanner:counter-engine",
    ":scanner:plant-engine",
    ":scanner:food-engine",
    ":scanner:wifi-engine",
)

// Android modules: UI, platform adapters, persistence and the apps.
val androidModules = listOf(
    ":core:design-system",
    ":data:local",
    ":core:capture",
    ":core:ocr",
    ":core:barcode",
    ":core:ml-runtime",
    ":core:model-packs",
    ":core:scanner-ui",
    ":scanner:qr",
    ":scanner:text",
    ":scanner:translation",
    ":scanner:expiry",
    ":scanner:math",
    ":scanner:product",
    ":app",
)

(jvmModules + if (jvmOnly) emptyList() else androidModules).forEach { path ->
    include(path)
}
