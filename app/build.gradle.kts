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
        versionCode = 5
        versionName = "0.3.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
