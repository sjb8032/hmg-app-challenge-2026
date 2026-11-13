import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 팀 공용 서명 키 정보(keystore.properties)는 git에 올리지 않는다.
// 파일이 없으면 release 빌드도 debug 키로 서명되어 설치·실행에는 문제가 없다.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// 장소 검색에 카카오 로컬을 쓰려면 local.properties(git에 올리지 않음)에 kakao.rest.key=… 를 넣는다.
// 없으면 키가 필요 없는 OpenStreetMap 검색을 쓴다.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val kakaoRestKey = (localProps.getProperty("kakao.rest.key") ?: System.getenv("KAKAO_REST_KEY") ?: "")
    .trim()
    .filter { it.isLetterOrDigit() }

android {
    // TODO: 앱 이름이 정해지면 namespace와 applicationId를 함께 바꾼다.
    namespace = "kr.contest.pleosapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.contest.pleosapp"
        minSdk = 28 // Pleos Connect 앱 요구사항: API 28 이상
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "KAKAO_REST_KEY", "\"$kakaoRestKey\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)

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
