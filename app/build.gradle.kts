plugins {
    id("com.android.application")
}

android {
    namespace = "com.netguard.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.netguard.ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.7.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
