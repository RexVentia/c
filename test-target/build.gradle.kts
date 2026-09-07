plugins {
    id("com.android.application")
}

android {
    namespace = "com.robot.simulator.testtarget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.robot.simulator.testtarget"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":bridge-contract"))
}
