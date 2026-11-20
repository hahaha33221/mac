plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 릴리스 서명: 환경 변수(SSHREMOTE_KEYSTORE 등)가 있으면 그 키로, 없으면 디버그 키로 서명
val releaseKeystore: String? = System.getenv("SSHREMOTE_KEYSTORE")

android {
    namespace = "com.example.sshremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sshremote"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("SSHREMOTE_VERSION_CODE") ?: "1").toInt()
        versionName = "0.1.${System.getenv("SSHREMOTE_VERSION_CODE") ?: "0"}"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SSHREMOTE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SSHREMOTE_KEY_ALIAS") ?: "sshremote"
                keyPassword = System.getenv("SSHREMOTE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // SSHJ · BouncyCastle 은 리플렉션을 써서 R8 축소를 끔 (MVP 단계)
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // SSH 엔진 + 안드로이드 기본 BC 를 대체할 전체 BouncyCastle (ed25519 · 최신 알고리즘)
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
}
