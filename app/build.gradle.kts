plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "top.clspd.apps.MyClipboardTransferHelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.clspd.apps.MyClipboardTransferHelper"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0-release-vn-2(ARB)"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../store.jks")
            storePassword = "123456"
            keyAlias = "defaultkey"
            keyPassword = "123456"
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}