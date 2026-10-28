import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

// Secrets and your own place stay out of git: local.properties (gitignored) may set
// tmdb.key=<TMDB v3 API key>, and weather.lat, weather.lon and weather.place.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "com.salutehome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.salutehome"
        // Android 9 (API 28) and up; this home runs on the Salute TV (Android 13).
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        // CloudWalker's App Store reads the Home app's versionName as <version>-<build>-<commit>-<type> and
        // crashes at start on anything shorter (CwHeaders splits it on "-" and takes the fourth part); the
        // Salute TV has no CloudWalker, but other CVTE boards do. The fourth part is Baron Home's, which worked.
        versionName = "1.0-0-salutehome-lite-com"
        // Empty: the shelf uses TVmaze, which needs no key.
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("tmdb.key", "")}\"")
        // Where the weather is for, and the name shown when there's no forecast line. Moscow unless
        // local.properties sets weather.lat, weather.lon and weather.place for your own place.
        buildConfigField("double", "WEATHER_LAT", localProps.getProperty("weather.lat", "55.7558"))
        buildConfigField("double", "WEATHER_LON", localProps.getProperty("weather.lon", "37.6173"))
        // The app the trending shelf's posters open (shelf.app=<package>); empty: the first app for films
        // and series in the dock.
        buildConfigField("String", "SHELF_APP", "\"${localProps.getProperty("shelf.app", "")}\"")
        buildConfigField("String", "WEATHER_PLACE", "\"${localProps.getProperty("weather.place", "Москва")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8 strips and optimises the code: a smaller APK and a faster start on the TV's slow CPU.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded over ADB, never published: signed with the debug key, as the launcher is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Framework views only, no AndroidX: the lightest home screen for a 512 MB TV.
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
