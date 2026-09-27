plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.kaloriku.wear"
    compileSdk = 37

    defaultConfig {
        // Must match the phone module: the Wear OS DataLayer only routes messages
        // between APKs that share an applicationId and signing certificate.
        applicationId = "id.kaloriku"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Same committed dev key as :phone. Identical signing certificate is required
    // for the DataLayer, and a stable one keeps `adb install -r` from forcing an
    // uninstall (which would delete /data/data). Dev key only.
    signingConfigs {
        create("dev") {
            val keystoreFile = rootProject.file(project.property("kaloriku.dev.keystore") as String)
            require(keystoreFile.isFile) {
                "Dev keystore not found at ${keystoreFile.path}. " +
                    "It is committed in the repo; restore it or regenerate with keytool."
            }
            storeFile = keystoreFile
            storePassword = project.property("kaloriku.dev.keystore.password") as String
            keyAlias = project.property("kaloriku.dev.key.alias") as String
            keyPassword = project.property("kaloriku.dev.key.password") as String
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    // Hosts the graph: SwipeDismissableNavHost + WearNavigator + the matching
    // `androidx.wear.compose.navigation.composable` destination builder.
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.tiles)
    implementation(libs.guava)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Raises the fragment version that the Wearable/Play-services transitives pin to
    // 1.2.4; registerForActivityResult requires >= 1.3.0 (lint InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit)
}
