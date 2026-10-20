plugins {
    id("java-library")
    id("net.fabricmc.fabric-loom-remap") version "1.16.1"
}

val minecraftVersion: String = "1.21.11"
val loaderVersion: String = "0.19.2"
val modVersion: String = "1.0.0"

group = "dev.metalboi"
version = modVersion

base {
    archivesName = "metalboi"
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.caffeinemc.net/releases") { name = "CaffeineMC Releases" }
    maven("https://maven.caffeinemc.net/snapshots") { name = "CaffeineMC Snapshots" }
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

// Sodium is only needed at compile time (it is an optional runtime integration).
// The coordinate is supplied via -Pmetalboi.sodium=<group:artifact:version> so that
// the build can be evaluated without it while the exact coordinate is being resolved.
val sodiumCoordinate: String? = (findProperty("metalboi.sodium") as String?)?.takeIf { it.isNotBlank() }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.layered { officialMojangMappings() })

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    if (sodiumCoordinate != null) {
        modCompileOnly(sodiumCoordinate)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_metalboi" }
    }
}
