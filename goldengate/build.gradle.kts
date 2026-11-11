// Golden Gate (github.com/Fitbit/golden-gate, Apache-2.0) built from the third_party/golden-gate submodule:
// the C core as libgg.so, its JNI layer as libxp.so, and the upstream Kotlin bindings.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val ggRoot = rootProject.file("third_party/golden-gate")
val upstreamKotlin = File(ggRoot, "platform/android/goldengate/GoldenGateBindings/src/main/kotlin")
val syncedKotlin = layout.buildDirectory.dir("generated/gg-kotlin").get().asFile

// Copy the upstream bindings, minus Stack.kt, which src/main/kotlin overrides with a small patch.
val syncGoldenGateKotlin by tasks.registering(Sync::class) {
    from(upstreamKotlin)
    exclude("com/fitbit/goldengate/bindings/stack/Stack.kt")
    into(syncedKotlin)
}

android {
    namespace = "com.fitbit.goldengate.bindings"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures { buildConfig = true }
    sourceSets["main"].java.srcDirs(syncedKotlin, "src/main/kotlin")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild") { dependsOn(syncGoldenGateKotlin) }

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.timber:timber:4.7.1")
}
