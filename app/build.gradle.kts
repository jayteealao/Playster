@Suppress("DSL_SCOPE_VIOLATION") // TODO: Remove once KTIJ-19369 is fixed
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("C:\\Users\\HP\\.android\\tasselsigningkey.jks")
            storePassword = "daddyy"
            keyAlias = "key0"
            keyPassword = "daddyy"
        }
        create("release") {
            storeFile = file("C:\\Users\\HP\\.android\\tasselsigningkey.jks")
            storePassword = "daddyy"
            keyAlias = "key0"
            keyPassword = "daddyy"
        }
    }
    namespace = "com.github.jayteealao.playster"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.github.jayteealao.playster"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    configurations {
        all {
            exclude(module = "httpclient")
            exclude(module = "commons-logging")
        }
    }
}

dependencies {

    implementation(libs.core.ktx)

//    compose
    implementation(platform(libs.compose.bom))

    implementation(libs.animation)
    implementation(libs.foundation)
    implementation(libs.runtime)
    implementation(libs.material)
    implementation(libs.material3)
    implementation(libs.ui)

    implementation(libs.ui.tooling)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.runtime.tracing)

//    compose interop
    implementation(libs.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.androidx.datastore.preferences)

    //credentials
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.identity)
    implementation(libs.one.tap.sign.`in`)
//    implementation(libs.appauth)
    implementation(libs.google.api.client.android) {
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "com.google.guava", module = "guava")

    }
    implementation(libs.google.api.services.youtube) {
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation(libs.google.api.client.gson)
    implementation(libs.google.http.client.android)
//    implementation(libs.bundles.credential)

    //hilt
    implementation(libs.hilt.android)
    implementation(libs.bundles.hilt)
//    ksp(libs.hilt.android.compiler)
//    ksp(libs.dagger.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.compilerx)

//    coil
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)

    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}