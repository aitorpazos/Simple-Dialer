plugins {
    id("com.android.library")
}

android {
    compileSdk = 34
    namespace = "com.duolingo.open.rtlviewpager"

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.viewpager:viewpager:1.0.0")
}
