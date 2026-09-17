import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 사용자가 공식 SDK에서 직접 배치한 Core만 로컬 빌드에 사용한다.
val verifyLocalCore by tasks.registering {
    val core = layout.projectDirectory.file("src/main/assets/character/lib/live2dcubismcore.min.js")
    val manifest = layout.projectDirectory.file("src/main/assets/character/import-manifest.json")
    inputs.files(core)
    inputs.file(manifest)
    doLast {
        val guide = "https://www.live2d.com/en/sdk/download/web/ 에서 약관 확인 후 Core를 직접 받아 배치하세요. docs/build-and-install.md 참조."
        if (!core.asFile.isFile || core.asFile.length() > 2 * 1024 * 1024) throw GradleException("로컬 Core 누락 또는 크기 오류. $guide")
        val contract = JsonSlurper().parse(manifest.asFile) as Map<*, *>
        val target = "lib/live2dcubismcore.min.js"
        if (contract["local_only_files"] != listOf(target)) throw GradleException("수동 Core 설치 계약 오류")
        val expected = (contract["files"] as List<*>).map { it as Map<*, *> }.single { it["target"] == target }["sha256"] as String
        val actual = MessageDigest.getInstance("SHA-256").digest(core.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) throw GradleException("로컬 Core 해시가 다릅니다. 기존 파일은 보존합니다. $guide")
        logger.lifecycle("로컬 Core 해시 검사 통과. 공개 배포 허가를 의미하지 않습니다.")
    }
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
    // JVM 소스 검사는 허용하되 APK/AAB의 assets는 설치된 Core를 필수 검사한다.
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(verifyLocalCore)
    if (name.startsWith("merge") && name.endsWith("AndroidTestAssets")) dependsOn(generateTlsTestCa)
    // Lint도 계측 assets를 읽는다. 검사와 시험 APK를 함께 빌드할 때 생성 순서를 보장한다.
    if ((name.startsWith("lintAnalyze") && name.endsWith("AndroidTest")) ||
        (name.startsWith("generate") && name.endsWith("AndroidTestLintModel"))) dependsOn(generateTlsTestCa)
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
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.webkit)

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
