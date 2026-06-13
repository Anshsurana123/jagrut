// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties
import java.io.FileInputStream

// Read AccessKey from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""
val bhashiniUserId = localProperties.getProperty("BHASHINI_USER_ID") ?: ""
val bhashiniApiKey = localProperties.getProperty("BHASHINI_ULCA_API_KEY") ?: ""
val sarvamApiKey = localProperties.getProperty("SARVAM_API_KEY") ?: ""
val elevenLabsApiKey = localProperties.getProperty("ELEVENLABS_API_KEY") ?: ""
val mongoDbConnectionString = localProperties.getProperty("MONGODB_CONNECTION_STRING") ?: ""
val mongoDbDatabase = localProperties.getProperty("MONGODB_DATABASE") ?: "jagrut_db"
val mongoDbCollection = localProperties.getProperty("MONGODB_COLLECTION") ?: "macros"
val mongoDbVectorIndex = localProperties.getProperty("MONGODB_VECTOR_INDEX") ?: "vector_index"

android {
    namespace = "com.example.jago"
    compileSdk = 34

    kotlin {
        jvmToolchain(17)
    }

    defaultConfig {
        applicationId = "com.example.jago"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "BHASHINI_USER_ID", "\"$bhashiniUserId\"")
        buildConfigField("String", "BHASHINI_ULCA_API_KEY", "\"$bhashiniApiKey\"")
        buildConfigField("String", "SARVAM_API_KEY", "\"$sarvamApiKey\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"$elevenLabsApiKey\"")
        buildConfigField("String", "MONGODB_CONNECTION_STRING", "\"$mongoDbConnectionString\"")
        buildConfigField("String", "MONGODB_DATABASE", "\"$mongoDbDatabase\"")
        buildConfigField("String", "MONGODB_COLLECTION", "\"$mongoDbCollection\"")
        buildConfigField("String", "MONGODB_VECTOR_INDEX", "\"$mongoDbVectorIndex\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "META-INF/native-image/org.mongodb/bson/native-image.properties"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.12.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.3") // Adding support for FileUtil

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // Google AI (Gemini)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Vosk
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("com.alphacephei:vosk-android:0.3.45")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:4.11.0")
    testImplementation("org.mockito:mockito-inline:4.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // MongoDB Java Driver
    implementation("org.mongodb:mongodb-driver-sync:5.1.0")
}
