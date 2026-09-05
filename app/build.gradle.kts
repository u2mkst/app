import org.gradle.api.JavaVersion

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.u2m.kp"

    // 안드로이드 16 규격 빌드 허용
    compileSdk = 36

    defaultConfig {
        applicationId = "com.u2m.kp"
        minSdk = 26

        // 안드로이드 14 태블릿 호환 타겟팅
        targetSdk = 34

        // 덮어쓰기 설치가 가능하도록 버전 상향
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 💡 웹뷰 앱은 찌꺼기 코드가 없어 true로 두면 알아서 엄청나게 가벼워집니다!
            isMinifyEnabled = true
            isShrinkResources = true

            // 2번 파일(proguard)이 없어도 에러가 나지 않도록 기본 규칙만 가리킵니다.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 💡 웹뷰 구동과 화면을 그리는 데 필요한 필수 순정 라이브러리만 남기고 다 지웠습니다.
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}