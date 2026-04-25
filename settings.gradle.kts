rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")

includeBuild("mediamp") {
    dependencySubstitution {
        substitute(module("org.openani.mediamp:mediamp-api")).using(project(":mediamp-api"))
        substitute(module("org.openani.mediamp:mediamp-mpv")).using(project(":mediamp-mpv"))
        substitute(module("org.openani.mediamp:mediamp-vlc")).using(project(":mediamp-vlc"))
        substitute(module("org.openani.mediamp:mediamp-internal-utils")).using(project(":mediamp-internal-utils"))
    }
}
