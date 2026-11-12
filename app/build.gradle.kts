import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 외부 API 키는 local.properties에서 읽는다 (깃에 올라가지 않는 파일)
//   opinet.key=오피넷 키
//   kma.key=공공데이터포털 기상청 단기예보 키
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun keyOf(name: String): String =
    "\"" + (localProps.getProperty(name) ?: "").trim().replace("\\", "").replace("\"", "") + "\""

android {
    namespace = "com.signallens.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.signallens.app"
        minSdk = 28          // Pleos 문서 기준 최소 API 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "OPINET_KEY", keyOf("opinet.key"))
        buildConfigField("String", "KMA_KEY", keyOf("kma.key"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.vehicle.sdk)

    // Pleos 음성 · 내비 SDK (차와 에뮬레이터의 한국어 음성, 음성 인식, 경로 정보)
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
