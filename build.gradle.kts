plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.serialization.get()}")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}

application {
    mainClass.set("ai.MainKt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$projectDir/config/detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
    autoCorrect = false
}

// Use the current Gradle JVM; the README documents JDK 17 as the minimum.

tasks {
    shadowJar {
        archiveBaseName.set("Markusaller")
        archiveClassifier.set("")
        archiveVersion.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
