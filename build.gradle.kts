plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "1.5.0"
}

ktlint {
    android.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

// ドライバー実装点（@InternalMapConductorApi）を使うためのオプトイン。
// android-for-* は地図SDKドライバーなので、モジュール単位で許可する。
kotlin {
    compilerOptions {
        optIn.add("com.mapconductor.core.InternalMapConductorApi")
    }
}

android {
    namespace = "com.mapconductor.arcgis"
    compileSdk = project.property("compileSdk").toString().toInt()

    defaultConfig {
        // ArcGIS Maps SDK for Kotlin 300.x requires minSdk 28.
        minSdk = maxOf(28, project.property("minSdk").toString().toInt())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        aarMetadata {
            minCompileSdk = project.property("compileSdk").toString().toInt()
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
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

// Publishing configuration
val libraryGroupId = project.findProperty("libraryGroupId") as String? ?: "com.mapconductor"
val libraryArtifactId = "for-arcgis"
val libraryVersion = project.findProperty("libraryVersion") as String? ?: "1.0.0"

dependencies {
    // CancellableContinuation.resume の 3 引数 onCancellation は coroutines 1.9 以降。
    // 明示しないと推移的に古い版が入り、単体ビルドだけがコンパイルエラーになる。
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom)) // BOM manages Compose artifact versions.
    implementation(libs.androidx.compose.runtime)
    //    implementation(libs.play.services.maps)
    implementation(libs.androidx.ui)
    compileOnly(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    // Lifecycle（MapView用）
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.common.java8)

    // ArcGIS SDK
    api(libs.arcgis.maps.kotlin)
    api(platform(libs.arcgis.maps.kotlin.toolkit.bom))
    // BOM管理の依存関係はPOMにバージョンが出力されないためMaven Central検証エラーになる
    // BOMと同じバージョンを明示することで解決
    api("com.esri:arcgis-maps-kotlin-toolkit-geoview-compose:${libs.versions.arcgisMapsKotlin.get()}")
    api("com.esri:arcgis-maps-kotlin-toolkit-authentication:${libs.versions.arcgisMapsKotlin.get()}")

    if (findProject(":android-sdk-compose") != null) {
        api(project(":android-sdk-compose"))
    } else {
        api("com.mapconductor:compose:$libraryVersion")
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

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

    if (project == rootProject) {
        // standalone build only — in multi-project (android-sdk), parent configures nmcp
        nmcp {
            publishAllPublicationsToCentralPortal {
                username.set(findProperty("ossrh_username") as String? ?: System.getenv("OSSRH_USERNAME") ?: "")
                password.set(findProperty("ossrh_password") as String? ?: System.getenv("OSSRH_PASSWORD") ?: "")
            }
        }
    }
}
