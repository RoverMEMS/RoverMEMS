plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.roverspi.memsgauge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.roverspi.memsgauge"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")

    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Workaround: AGP 9.3.1's built-in Kotlin compiler places unit test classes under
// build/intermediates/built_in_kotlinc/, but the testDebugUnitTest task's classpath
// doesn't yet pick that directory up automatically, causing a ClassNotFoundException
// for every test class. Add it explicitly until AGP fixes the wiring.
afterEvaluate {
    tasks.named<Test>("testDebugUnitTest") {
        val builtInKotlincDir = layout.buildDirectory.dir(
            "intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"
        )
        testClassesDirs += files(builtInKotlincDir)
        classpath += files(builtInKotlincDir)
    }
}
