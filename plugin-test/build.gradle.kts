plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.paperink.plugintest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dsh.paperink.plugintest"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 只依赖纯接口 AAR，不引任何第三方库：插件应尽可能轻。
dependencies {
    implementation(project(":plugin-api"))
}
