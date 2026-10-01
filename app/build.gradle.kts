import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.deuty.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deuty.agent"
        // 26 = Android 8.0. Below that, TYPE_APPLICATION_OVERLAY and several
        // AccessibilityNodeInfo actions do not exist, and the agent would need
        // a second code path that nobody can test. 30+ unlocks screenshots.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-mvp"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        // Optional local debug signing via keystore.properties (never committed).
        val keystorePropsFile = rootProject.file("keystore.properties")
        if (keystorePropsFile.exists()) {
            val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
            create("local") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug builds are the ones used for development; keep them fast.
            isMinifyEnabled = false
        }
        release {
            // R8 is intentionally OFF for V1. The agent's whole job is calling
            // into framework accessibility APIs and being reachable from the
            // system (service declared in the manifest); a bad keep rule here
            // fails at runtime on a real device, not at build time. Turn it on
            // once the on-device test matrix in docs/TESTING.md is green.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("local")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        // View binding replaces findViewById: layout ids are checked at compile
        // time instead of failing with a null view at runtime.
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // core/json codecs use org.json. android.jar only ships throwing stubs, so the
    // reference implementation is needed for JVM tests. Not in the APK.
    testImplementation(libs.json)
}
