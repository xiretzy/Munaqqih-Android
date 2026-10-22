plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.munaqqih.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.munaqqih.prototype"
        minSdk = 24          // Chaquopy يحتاج 24 كحد أدنى
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        ndk {
            // جوال حقيقي فقط. للمحاكي أضف "x86_64"
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        // Python 3.10: آخر نسخة فيها scikit-learn جاهز في مستودع Chaquopy
        version = "3.10"

        // لازم يكون عندك Python 3.10 على جهازك. لو ما لقاه، حط المسار هنا:
        // buildPython("C:/Users/xiretzy/AppData/Local/Programs/Python/Python310/python.exe")

        pip {
            install("numpy")
            install("pandas")
            install("scikit-learn")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
