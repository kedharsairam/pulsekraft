plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android here, deliberately. AGP 9 brings
    // its own Kotlin support, and applying the plugin as well registers
    // the `kotlin` extension twice. The rest of this workspace does it
    // the same way; matching them is worth more than matching an older
    // template.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.krafttools.pulsekraft"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.krafttools.pulsekraft"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Debug-signed on purpose, and stated in the README. There is
            // no release keystore for this project: no store distribution,
            // GitHub releases only. A user who sideloads gets a build the
            // author also built, rather than one that came from a pipeline
            // neither of us can inspect.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // No HTTP client yet, on purpose.
    //
    // The measurement engine cannot use a stock client, and the reason is
    // in core/Buffer.kt: `setReceiveBufferSize` has to be called *before*
    // connect, or the receive window the kernel advertises is never
    // enlarged, and the app reports its own buffer size dressed up as the
    // user's line. OkHttp does not expose the socket before the handshake
    // runs, so this will be a hand-rolled HTTP/1.1 client over a socket
    // factory we own. Adding OkHttp now would be adding it twice.
    //
    // This is also the first Kraft app that needs INTERNET, which makes
    // the absence of a network library worth a note rather than a gap.

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // `debug`, not `androidTest`. This artefact contributes the host
    // activity that Compose's test rule launches, and it has to be
    // merged into the app being tested — declared only for the test APK
    // it lands in the test APK's own process, and every test fails with
    // "Intent resolved to different process" before it runs a line.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
