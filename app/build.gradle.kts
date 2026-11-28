plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.hadesdray.mamaison"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.hadesdray.mamaison"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "1.9.0"
    }

    // Lecteur VLC lourd : un fichier par type de processeur (chaque appareil ne télécharge que le sien)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // Clé de signature fixe : indispensable pour que les mises à jour s'installent par-dessus
    signingConfigs {
        create("fixe") {
            storeFile = file("maison.keystore")
            storePassword = "ma-maison"
            keyAlias = "maison"
            keyPassword = "ma-maison"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixe") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("fixe")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-rtsp:2.19.1")
    // Lecteur de secours tous formats (gratuit) pour les vidéos qu'ExoPlayer ne lit pas
    implementation("org.videolan.android:libvlc-all:3.6.0")
    // Partages Windows (gratuit, libre)
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
}
