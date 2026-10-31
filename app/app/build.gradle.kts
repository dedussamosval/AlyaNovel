plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val serverUrl = (project.findProperty("serverUrl") as String?) ?: "https://manga.example.com"
val debugServerUrl = (project.findProperty("debugServerUrl") as String?) ?: serverUrl

android {
    namespace = "org.dedussamosval.alya"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.dedussamosval.alya"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-alpha"
        buildConfigField("String", "SERVER_URL", quoted(serverUrl))
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", quoted(debugServerUrl))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подпись релиза настройте в Android Studio: Build → Generate Signed App Bundle / APK
        }
    }

    applicationVariants.all {
        val v = this
        v.outputs.map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }.forEach { output ->
            output.outputFileName = "alyanovel-${v.versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
