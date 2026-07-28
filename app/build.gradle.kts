import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Device-side config (plan G006): read from `local.properties` (gitignored — never
// committed, see docs/dev-loop.md "Credential placement"). Missing keys default to
// empty/blank; MainActivity/AppConfig fail closed (TOKEN-FAIL / DEGRADED) rather than crash.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}
fun localProp(key: String, default: String = ""): String = localProperties.getProperty(key, default)

android {
    namespace = "com.woolab.lumella"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.woolab.lumella"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // local.properties keys (see docs/dev-loop.md):
        //   lumella.tokenServiceBaseUrl — token-service base URL (e.g. http://<mac-lan-ip>:8788)
        //   lumella.lumaBaseUrl         — luma-api base URL (e.g. http://<mac-lan-ip>:8010)
        //   lumella.localToken          — shared local pairing secret (X-Lumella-Local-Token), NOT an API key
        //   lumella.brainClassName      — TutorBrain impl FQCN for runtime DI (default: LumaTutorBrain)
        //   lumella.brainEmail/lumella.brainPassword — luma account credentials for BrainCredentials
        buildConfigField("String", "TOKEN_SERVICE_BASE_URL", "\"${localProp("lumella.tokenServiceBaseUrl", "http://10.0.2.2:8788")}\"")
        buildConfigField("String", "LUMA_BASE_URL", "\"${localProp("lumella.lumaBaseUrl", "http://10.0.2.2:8010")}\"")
        buildConfigField("String", "LUMELLA_LOCAL_TOKEN", "\"${localProp("lumella.localToken", "")}\"")
        buildConfigField("String", "BRAIN_CLASS_NAME", "\"${localProp("lumella.brainClassName", "com.woolab.lumella.adapter.LumaTutorBrain")}\"")
        buildConfigField("String", "BRAIN_EMAIL", "\"${localProp("lumella.brainEmail", "")}\"")
        buildConfigField("String", "BRAIN_PASSWORD", "\"${localProp("lumella.brainPassword", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        // Required for BaseMirrorActivity<ActivityMainBinding> (Mercury SDK dual-eye
        // ViewBinding pair, see MainActivity/mBindingPair).
        viewBinding = true
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

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":tutor-contract"))
    // :luma-adapter is NOT declared here (implementation/api) — the DependencyRuleGuardTest
    // (:contract-tests) enforces that :app stays free of a compile-time coupling to the
    // engine adapter. The concrete TutorBrain impl is DI-bound at RUNTIME only via
    // BrainFactory's reflective Class.forName lookup (see brain/BrainFactory.kt); this
    // runtimeOnly dependency puts the class on the installed APK's runtime classpath
    // without touching debugCompileClasspath (verify: `./gradlew :app:dependencies
    // --configuration debugCompileClasspath | grep -c luma-adapter` -> 0).
    runtimeOnly(project(":luma-adapter"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // CameraX (LEGACY-ELLA-proven capture recipe on RayNeo, G006 photo fix)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    // Required by the Mercury SDK: BaseEventActivity.mappingAction() resolves
    // androidx.lifecycle.LifecycleOwnerKt (lifecycleScope) at runtime. Without these the
    // SDK crashes with NoClassDefFoundError on the first touchpad SLIDE gesture — taps
    // never hit that path, which is why it survived every tap-only test. LEGACY ELLA
    // carries the same two artifacts.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // WS client for OpenAiRealtimeTransport (plan G006 decision: ELLA used okhttp for its
    // Realtime WS connection; :app had no HTTP/WS dependency before this — okhttp is the
    // only new dependency this change adds, version pinned to the ELLA-main baseline).
    implementation(libs.okhttp)
    implementation(libs.androidx.constraintlayout)
    // RayNeo Mercury SDK (native-glasses classification; see LumellaApp/MainActivity).
    // Copied from TUTOR/LEGACY/ELLA/app/libs — same file, not a compile-time coupling
    // to LEGACY (no source/module dependency, just the shared AAR artifact).
    implementation(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
