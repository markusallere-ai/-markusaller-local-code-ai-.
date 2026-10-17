import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.bhlangonijr:chesslib:1.3.7")
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
    val runnableJar by registering {
        dependsOn(shadowJar)
        val sourceJar = shadowJar.flatMap { it.archiveFile }
        val runnableJarFile = layout.projectDirectory.file("Markusaller.jar")
        inputs.file(sourceJar)
        outputs.file(runnableJarFile)
        doLast {
            Files.copy(
                sourceJar.get().asFile.toPath(),
                runnableJarFile.asFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
    build {
        dependsOn(runnableJar)
    }
}
