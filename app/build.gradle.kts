plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 기기 시험에 고정 만료 인증서나 개인키를 커밋하지 않는다.
val tlsTestAssets = layout.buildDirectory.dir("generated/tlsTestAssets")
val generateTlsTestCa by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("tools/GenerateTestCa.java"))
    outputs.dir(tlsTestAssets)
    outputs.upToDateWhen { false }
    val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    commandLine(file("${System.getProperty("java.home")}/bin/$javaName"), "-Dfile.encoding=UTF-8",
        rootProject.file("tools/GenerateTestCa.java"), tlsTestAssets.get().file("ca.der").asFile)
}

android {
    namespace = "dev.ene.companion"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.ene.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["test"].resources.srcDir(rootProject.file("contracts/companion/v1"))
    sourceSets["androidTest"].assets.srcDir(tlsTestAssets)
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("AndroidTestAssets")) dependsOn(generateTlsTestCa)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
