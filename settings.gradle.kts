pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") {
            content {
                includeModule("com.github.k2-fsa.sherpa-onnx", "sherpa-onnx")
            }
        }
    }
}

rootProject.name = "family-vlog"
include(":app")
