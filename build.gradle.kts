import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

// Central Portal 命名空间为 io.github.<GitHub 用户名>，与包名 io.github.hatoyuze.deepseek.* 无关
group = "io.github.hatoyuze"
version = "0.1.0"

repositories {
    if (System.getenv("CI")?.toBoolean() != true) {
        // maven("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvm()
    // Apple 平台（需在 macOS 上构建/发布，见 .github/workflows/publish.yml）
    // macosX64 自 Kotlin 2.3.20 起已废弃，Apple Silicon 下使用 macosArm64
    macosArm64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    // 其他桌面/Web 平台
    linuxX64()
    linuxArm64()
    mingwX64()
    js {
        nodejs()
        browser()
        binaries.library()
    }
    wasmJs {
        nodejs()
        binaries.library()
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.slf4j.simple)
            implementation(libs.kotlin.logging)
        }

        // 各平台默认 HTTP 引擎：与 JVM 现状一致，保证库开箱即用；
        // 使用者仍可通过 HttpClient(engine) 显式指定其他引擎。
        nativeMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(libs.mordant)
            implementation(libs.mordant.markdown)
        }
    }
}

// ---------------------------------------------------------------------------
// Maven Central (Sonatype Central Portal) 发布配置
// 使用 com.vanniktech.maven.publish 插件：
//  - `./gradlew publishAndReleaseToMavenCentral` 打包上传并自动发布
//    （由 .github/workflows/publish.yml 在 macOS runner 上执行）
//  - 凭据通过环境变量注入：
//      ORG_GRADLE_PROJECT_mavenCentralUsername / mavenCentralPassword（Portal token）
//      ORG_GRADLE_PROJECT_signingInMemoryKey / signingInMemoryKeyPassword（GPG 私钥）
//  - 未配置签名密钥时只影响发布任务，不影响日常 build/test
// ---------------------------------------------------------------------------

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates("io.github.hatoyuze", "deepseek-helper", version.toString())
    pom {
        name.set("DeepSeek Helper")
        description.set("Kotlin Multiplatform wrapper for the DeepSeek chat APIs with a tool-calling pipeline.")
        url.set("https://github.com/HatoYuze/deepseek-helper")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("hatoyuze")
                name.set("Yukiky")
                url.set("https://github.com/HatoYuze")
            }
        }
        scm {
            url.set("https://github.com/HatoYuze/deepseek-helper")
            connection.set("scm:git:git://github.com/HatoYuze/deepseek-helper.git")
            developerConnection.set("scm:git:ssh://git@github.com:HatoYuze/deepseek-helper.git")
        }
    }
}

tasks.register("printVersion") {
    group = "help"
    description = "打印项目版本号（发布工作流用于校验 tag 与版本一致）"
    doLast {
        println(project.version)
    }
}
