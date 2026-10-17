plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.birdlog.app"
    compileSdk = 36
    defaultConfig { applicationId = "org.birdlog.app"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "1.0.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("io.rebble.pebblekit2:client:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
