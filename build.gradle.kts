// Modern AGP version compatible with a fresh IDE install
plugins {
    id("com.android.application") version "8.4.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
