plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.github.hatoyuze"
version = "0.1.0"

repositories {
    if (System.getenv("CI")?.toBoolean() != true) {
        // maven("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
}

kotlin {
    jvm()

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

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(libs.mordant)
            implementation(libs.mordant.markdown)
        }
    }
}
