plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "com.prettysmartlabs.ble_connection_manager"
    compileSdk = 35

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate {
                // Tell Maven Publish where to get the artifact (the AAR)
                from(components["release"])
                // Provide additional POM metadata if needed
                groupId = "com.github.JohanGarridoPSL"
                artifactId = "ble-connection-manager"
                version = "1.2.0"
            }
        }
    }
}
