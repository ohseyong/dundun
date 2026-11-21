import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.tetherfree"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tetherfree"
        // am instrument --no-restart 는 안드로이드 13부터 있다.
        minSdk = 33
        targetSdk = 36
        versionCode = 8
        versionName = "1.4.0"
        // 삼성 갤럭시는 모두 arm64 다.
        ndk { abiFilters += "arm64-v8a" }
    }

    // 배포용 서명 키는 저장소 밖에 둔다. 파일 위치는 keystore.properties(저장소에 올리지 않음)나
    // TETHER_KEYSTORE_PROPERTIES 로 알려 준다. 없으면 디버그 키로 서명한다.
    val keystoreProps = (System.getenv("TETHER_KEYSTORE_PROPERTIES")?.let { file(it) }
        ?: rootProject.file("keystore.properties")).takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    // 휴대전화 설정의 '앱별 언어'에서 고를 수 있게 한다.
    androidResources { generateLocaleConfig = true }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
}
