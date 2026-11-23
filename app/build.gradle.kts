plugins { id("com.android.application") }

android {
    namespace = "dev.wirelessadb.autostart"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.wirelessadb.autostart"
        minSdk = 28
        targetSdk = 36
        versionCode = 19
        versionName = "1.0.18"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
