plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment, so no key is ever in the repo. GitHub Actions sets AIRBUZZ_KEYSTORE (a
// PKCS12 file), AIRBUZZ_KEYSTORE_PASSWORD (for the store and every entry) and AIRBUZZ_KEY_ALIAS: "airbuzz", the app
// signing key, which Play also takes as the upload key, for both the APK and the bundle. Without them a release build is unsigned
// and debug builds are signed with the machine's debug key, as before.
val releaseKeystore: File? = System.getenv("AIRBUZZ_KEYSTORE")?.takeIf { it.isNotBlank() }?.let(::file)

// The licence texts ship inside the app as assets under legal/, for the About screen: GPL-3.0's "Appropriate Legal
// Notices" and Apache-2.0 section 4 (Golden Gate's NOTICE is in licenses/).
val licenceAssets = layout.buildDirectory.dir("generated/licence-assets")
val syncLicenceAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("licenses")) { into("licenses") }
    into(licenceAssets.map { it.dir("legal") })
}

android {
    namespace = "com.davort.airbuzz"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.davort.airbuzz"
        minSdk = 28
        targetSdk = 36
        versionCode = 54
        versionName = "0.6.32"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storeType = "pkcs12"
            storePassword = System.getenv("AIRBUZZ_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("AIRBUZZ_KEY_ALIAS") ?: "airbuzz"
            keyPassword = storePassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    sourceSets["main"].assets.srcDir(licenceAssets)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

tasks.named("preBuild") { dependsOn(syncLicenceAssets) }

dependencies {
    implementation(project(":goldengate"))
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.50")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
}
