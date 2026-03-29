pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            // ArcGIS Maven repository
            url = uri("https://esri.jfrog.io/artifactory/arcgis")
        }
        maven {
            name = "GithubPackages"
            url = uri("https://maven.pkg.github.com/mapconductor/android-for-arcgis")
            credentials {
                username = System.getenv("GPR_USER") ?: ""
                password = System.getenv("GPR_TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "mapconductor-for-arcgis"
include(":sample-app")
