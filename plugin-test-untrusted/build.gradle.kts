plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.paperink.plugintest.evil"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dsh.paperink.plugintest.evil"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-evil"
    }

    // 第三方签名：与宿主 debug.keystore 不同的证书，用来验证宿主白名单拦截。
    //
    // 该 keystore 不入库（属于测试材料，.gitignore 已排除 .work/），
    // 所以开源仓库里直接构建时这里会自动跳过第三方签名、改用默认 debug 证书；
    // QA 本机存在 .work/keystore/evil.jks 时仍走第三方签名，验证「不可信插件被拒」。
    val evilKeystore = rootProject.file(".work/keystore/evil.jks")
    if (evilKeystore.exists()) {
        signingConfigs {
            create("thirdParty") {
                storeFile = evilKeystore
                storePassword = "paperink123"
                keyAlias = "evil"
                keyPassword = "paperink123"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (evilKeystore.exists()) {
                signingConfig = signingConfigs.getByName("thirdParty")
            }
        }
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

dependencies {
    implementation(project(":plugin-api"))
}
