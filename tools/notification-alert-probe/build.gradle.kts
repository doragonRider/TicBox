plugins { id("com.android.application") version "9.5.1" }

android {
    namespace = "dev.ticbox.alertprobe"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.ticbox.alertprobe"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { task ->
            task.maxHeapSize = "1g"
            listOf("robolectric.dependency.repo.url", "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach { key -> System.getProperty(key)?.let { task.systemProperty(key, it) } }
        }
    }
    lint { warningsAsErrors = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
