plugins {
    java
    // Builds the server-side plugin zip and generates teamcity-plugin.xml from the
    // descriptor block below. See https://github.com/rodm/gradle-teamcity-plugin
    id("io.github.rodm.teamcity-server") version "1.5.5"
}

group = "com.eborchardt.teamcity"
version = "0.1.0"

repositories {
    mavenCentral()
    // TeamCity API artifacts (org.jetbrains.teamcity:server-api, etc.).
    maven { url = uri("https://download.jetbrains.com/teamcity-repository") }
}

java {
    // TeamCity 2024+ server runs on Java 17. Keep the plugin at the server baseline.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

teamcity {
    // The rodm plugin puts the matching server-api on the `provided` classpath.
    version = (project.findProperty("teamcityVersion") as String?) ?: "2026.2.1"

    server {
        descriptor {
            name = "s3-storage-selector"
            displayName = "S3 Artifacts Storage Selector"
            version = project.version.toString()
            vendorName = "eborchardt"
            vendorUrl = "https://github.com/eborchardt"
            description = "Redirects artifact publishing to a pre-configured (inactive) S3 " +
                "storage for builds that run on agents flagged with " +
                "teamcity.artifacts.useS3Storage. Server-side only."
            // Isolate the plugin's classes from other plugins.
            useSeparateClassloader = true
        }
    }
}

dependencies {
    // ArtifactsStorageSettingsManager, BuildPromotionEx, BuildAttributes, ArtifactStorageSettings,
    // BuildLog and MessageAttrs live in TeamCity's server implementation, not the slim server-api
    // that `teamcity.version` puts on the classpath. Add the internal server artifact (provided:
    // present on the running server at runtime, so not bundled in the plugin).
    provided("org.jetbrains.teamcity.internal:server:${teamcity.version}")

    // Annotations used by the processor (org.jetbrains.annotations) come in transitively
    // via the TeamCity `provided` API, so no explicit compile dependency is needed here.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}
