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
        name = "文件"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "242"
        }

        description = """
            文件提供一个独立的外部目录浏览工具窗口，可在不加入 IntelliJ IDEA Project Model 的情况下预览和打开项目外文件。
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
