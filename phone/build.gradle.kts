plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.kaloriku.phone"
    compileSdk = 37

    defaultConfig {
        // The phone and wear apps MUST share the same applicationId (and signing
        // key) for the Wear OS DataLayer to route messages between them.
        applicationId = "id.kaloriku"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // Debug builds are signed with the committed dev key (see gradle.properties)
    // so the certificate stays stable across machines and clean checkouts. A
    // changing certificate makes `adb install -r` fail and tempts an uninstall,
    // which deletes /data/data and the food log with it.
    // Dev key only -- a Play release needs a real key from CI secrets.
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
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // Raises the fragment version that the Wearable/Play-services transitives pin to
    // (<1.3.0), which otherwise trips InvalidFragmentVersionForActivityResult.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit)
}
