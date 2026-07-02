plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "sidecar.jhacker.cn"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.5")
        pluginVerifier()
    }

    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        id = "sidecar.jhacker.cn"
        name = "Sidecar Files"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "242"
        }

        description = """
            Sidecar Files provides an independent external folder browser inside IntelliJ IDEA.
            It lets you preview and open local folders outside the current project without adding them to the Project Model,
            changing module settings, or triggering Maven or Gradle import.
        """.trimIndent()

        changeNotes = """
            <ul>
              <li>Initial MVP with external folder roots, lazy tree browsing, file opening, refresh, remove, copy path, and reveal actions.</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
