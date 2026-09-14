import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 서명 정보는 저장소에 넣지 않는다. keystore.properties 가 없으면 서명 없이 빌드한다.
// 30EBSE 는 전용 릴리스 키로 서명한다 — 키가 어디 있는지는 CLAUDE.md 에.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.artbrain.ebse"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.artbrain.ebse"
        minSdk = 30        // Poke4 Lite = Android 11
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    // PdfBox 가 암호 걸린 PDF 를 풀려고 BouncyCastle 을 데려오는데, 딸려 오는 양자내성
    // 암호 표(pqc/*.properties)만 8MB 다. 우리가 쓸 일이 없는 것이라 꾸릴 때 뺀다.
    packaging {
        resources {
            excludes += "org/bouncycastle/pqc/**"
        }
    }

    buildTypes {
        // 디버그 판은 이름을 달리해 릴리스 판(다른 키)과 한 기기에 같이 둔다.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // 변환기 시험은 JVM 에서 돈다. 안드로이드 틀의 빈 껍데기가 예외 대신 기본값을 내게 한다.
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // 구글 문서는 드라이브가 PDF 로만 내준다 — 거기서 글을 뽑는다. 여느 PDF 도 읽는다.
    implementation(libs.pdfbox.android)
    // txt·md·srt 의 인코딩을 알아맞힌다 (EUC-KR·MS949 자막이 흔하다).
    implementation(libs.juniversalchardet)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
