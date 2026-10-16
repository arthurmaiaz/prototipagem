import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// URL e token do Apps Script ficam no local.properties (fora do Git).
// Sem eles, o app usa o MockReservaApi (ver ReservaApiFactory).
val localProperties = Properties().apply {
    val arquivo = rootProject.file("local.properties")
    if (arquivo.exists()) {
        arquivo.inputStream().use { load(it) }
    }
}

// eNBioBSP SDK da Nitgen (leitor Hamster DX). É proprietário e não vai para o
// repositório público: cada um copia o .aar para app/libs/ (ver app/libs/README.md).
val sdkNitgen = file("libs/nbiobspjni-2.0.0.10.aar")
if (!sdkNitgen.exists()) {
    throw GradleException(
        "SDK da Nitgen não encontrado em app/libs/nbiobspjni-2.0.0.10.aar. Veja app/libs/README.md.")
}

fun stringJava(valor: String): String =
    "\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "br.unifor.cct"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "br.unifor.cct"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APPS_SCRIPT_URL",
            stringJava(localProperties.getProperty("appsScript.url", "")))
        buildConfigField("String", "APPS_SCRIPT_TOKEN",
            stringJava(localProperties.getProperty("appsScript.token", "")))
        buildConfigField("String", "NITGEN_SERIAL",
            stringJava(localProperties.getProperty("nitgen.serial", "")))
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation(files(sdkNitgen))
}