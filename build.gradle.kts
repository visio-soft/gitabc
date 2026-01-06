import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.21"
    id("org.jetbrains.compose") version "1.5.11"
}

group = "com.visiosoft"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
}

tasks.register<JavaExec>("runCoreTest") {
    group = "verification"
    description = "Run core functionality test"
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["test"].runtimeClasspath
    mainClass.set("com.visiosoft.gitabc.CoreTestKt")
}

compose.desktop {
    application {
        mainClass = "com.visiosoft.gitabc.MainKt"
        
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "GitABC"
            packageVersion = "1.0.0"
            description = "A multiplatform Git client for macOS and Linux"
            vendor = "Visio Soft"
            
            val iconsRoot = project.file("src/main/resources")
            
            macOS {
                val icnsFile = project.file("src/main/resources/icon.icns")
                if (icnsFile.exists()) {
                    iconFile.set(icnsFile)
                }
            }
            
            linux {
                val pngFile = project.file("src/main/resources/icon.png")
                if (pngFile.exists()) {
                    iconFile.set(pngFile)
                }
            }
        }
    }
}
