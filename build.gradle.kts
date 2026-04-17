plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jlleitschuh.ktlint)
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "0.0.8"
}

ktlint {
    android.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

android {
    namespace = "com.mapconductor.arcgis"
    compileSdk = project.property("compileSdk").toString().toInt()

    defaultConfig {
        minSdk = project.property("minSdk").toString().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion =
            project.property("kotlinCompilerExtensionVersion").toString()
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(project.property("javaVersion").toString())
        targetCompatibility = JavaVersion.toVersion(project.property("javaVersion").toString())
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(
                project.property("jvmTarget").toString(),
            ),
        )
    }
}

// Publishing configuration
val libraryGroupId = project.findProperty("libraryGroupId") as String? ?: "com.mapconductor"
val libraryArtifactId = "for-arcgis"
val libraryVersion = project.findProperty("libraryVersion") as String? ?: "1.0.0"

dependencies {

    implementation(libs.androidx.compose.runtime)
    //    implementation(libs.play.services.maps)
    compileOnly(libs.androidx.ui)
    compileOnly(libs.androidx.ui.tooling.preview)
    compileOnly(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom)) // ← bomでバージョン合わせる
    // Lifecycle（MapView用）
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.common.java8)

    // ArcGIS SDK
    implementation(libs.arcgis.maps.kotlin)
    implementation(platform(libs.arcgis.maps.kotlin.toolkit.bom))
    // BOM管理の依存関係はPOMにバージョンが出力されないためMaven Central検証エラーになる
    // BOMと同じバージョンを明示することで解決
    implementation("com.esri:arcgis-maps-kotlin-toolkit-geoview-compose:${libs.versions.arcgisMapsKotlin.get()}")
    implementation("com.esri:arcgis-maps-kotlin-toolkit-authentication:${libs.versions.arcgisMapsKotlin.get()}")

    if (findProject(":android-sdk-core") != null) {
        implementation(project(":android-sdk-core"))
    } else {
        implementation("com.mapconductor:core:$libraryVersion")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Set project version for NMCP plugin
version = libraryVersion
val libraryName = "MapConductor for ArcGIS"
val libraryDescription = "ArcGIS Maps implementation for MapConductor unified mapping library"

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    // Since Android libraries don't have javadoc task by default, create empty jar
}

publishing {
    publications {
        create<MavenPublication>("release") {
            project.afterEvaluate {
                from(components["release"])
            }

            groupId = libraryGroupId
            artifactId = libraryArtifactId
            version = libraryVersion

            artifact(javadocJar.get())

            pom {
                name.set(libraryName)
                description.set(libraryDescription)
                url.set(
                    project.findProperty("libraryUrl") as String?
                        ?: "https://github.com/MapConductor/android-for-arcgis",
                )

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set(project.findProperty("developerId") as String? ?: "mapconductor")
                        name.set(project.findProperty("developerName") as String? ?: "MapConductor Team")
                        email.set(project.findProperty("developerEmail") as String? ?: "info@mkgeeklab.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/MapConductor/android-for-arcgis.git")
                    developerConnection
                        .set("scm:git:ssh://github.com:MapConductor/android-for-arcgis.git")
                    url.set(
                        project.findProperty("scmUrl") as String?
                            ?: "https://github.com/MapConductor/android-for-arcgis.git",
                    )
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            setUrl("https://maven.pkg.github.com/MapConductor/android-for-arcgis")
            credentials {
                username =
                    project.findProperty("gpr.user") as String? ?: System.getenv("GPR_USER")
                        ?: System.getenv("GITHUB_ACTOR")
                password =
                    project.findProperty("gpr.key") as String? ?: System.getenv("GPR_TOKEN")
                        ?: System.getenv("GITHUB_TOKEN")
            }
        }

    }
}

signing {
    val signingKey = findProperty("signingKey") as String?
    val signingPassword = findProperty("signingPassword") as String?
    if (!signingKey.isNullOrEmpty() && !signingPassword.isNullOrEmpty()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["release"])
    }
}

nmcp {
    publish("release") {
        username = findProperty("ossrh_username") as String? ?: System.getenv("OSSRH_USERNAME") ?: ""
        password = findProperty("ossrh_password") as String? ?: System.getenv("OSSRH_PASSWORD") ?: ""
    }
}

