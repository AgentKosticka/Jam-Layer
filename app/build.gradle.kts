plugins { id("com.android.application") }
android {
    namespace = "app.morphe.jam.companion"
    compileSdk = 36
    defaultConfig { applicationId = "app.morphe.jam.companion"; minSdk = 26; targetSdk = 37; versionCode = 2; versionName = "1.0.1"; testInstrumentationRunner = "app.morphe.jam.companion.DeviceScenario" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    signingConfigs {
        create("release") {
            val storePath = System.getenv("JAM_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) storeFile = file(storePath)
            storePassword = System.getenv("JAM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("JAM_KEY_ALIAS")
            keyPassword = System.getenv("JAM_KEY_PASSWORD")
        }
    }
    buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("release") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
