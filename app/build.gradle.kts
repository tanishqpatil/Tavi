plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The prototype stays outside the portable APK until its real-model qualification passes.
val liteRtLab = providers.gradleProperty("taviLiteRt").orElse("false").get().also {
    require(it in setOf("true", "false"))
}.toBoolean()

android {
    namespace = "app.lfm3.probe"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.lfm3.probe"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("taviVersionCode").get().toInt().also {
            require(it in 1..2_100_000_000) { "Invalid Android version code." }
        }
        versionName = providers.gradleProperty("taviVersionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        val kleidi = providers.gradleProperty("taviKleidiAi").orElse("false").get().also { require(it in setOf("true", "false")) }.toBoolean()
        val opencl = providers.gradleProperty("taviOpenCl").orElse("false").get().also { require(it in setOf("true", "false")) }.toBoolean()
        buildConfigField("boolean", "CPU_KLEIDIAI_COMPILED", kleidi.toString())
        buildConfigField("boolean", "OPENCL_COMPILED", opencl.toString())
        buildConfigField("boolean", "LITERT_EXPERIMENTAL", liteRtLab.toString())
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DTAVI_KLEIDIAI=${if (kleidi) "ON" else "OFF"}", "-DTAVI_OPENCL=${if (opencl) "ON" else "OFF"}") } }
        if (opencl) externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.named("main") {
        kotlin.srcDir(if (liteRtLab) "src/litert/java" else "src/portable/java")
    }
    if (liteRtLab) sourceSets.named("test") { kotlin.srcDir("src/litertTest/java") }
    if (liteRtLab) sourceSets.named("androidTest") { kotlin.srcDir("src/litertAndroidTest/java") }
    // Release credentials enter only through the build environment. Never store passwords in Gradle properties.
    val releaseStore = providers.environmentVariable("TAVI_SIGNING_KEYSTORE").orNull
    if (releaseStore != null) signingConfigs.create("release") {
        storeFile = file(releaseStore)
        storePassword = providers.environmentVariable("TAVI_SIGNING_STORE_PASSWORD").get()
        keyAlias = providers.environmentVariable("TAVI_SIGNING_KEY_ALIAS").get()
        keyPassword = providers.environmentVariable("TAVI_SIGNING_KEY_PASSWORD").get()
        storeType = "PKCS12"
    }
    buildTypes.named("release") {
        isDebuggable = false
        // Keep the first distributable build behavior aligned with the qualified runtime.
        // R8/resource shrinking need a separate JNI/reflection regression gate.
        isMinifyEnabled = false
        if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // This library is a link-time SDK dependency. Runtime uses the phone vendor's driver.
        jniLibs.excludes += "**/libOpenCL.so"
    }
}

dependencies {
    if (liteRtLab) implementation("com.google.ai.edge.litert:litert:2.3.0")
    // API 36 is the highest installed SDK in this workspace. Keep this probe buildable
    // with the matching Compose generation; revisit after the P1 SDK/device inventory.
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
