plugins { alias(libs.plugins.kotlin.jvm); id("java-library") }
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api(project(":core:api"))
    implementation(project(":core:protocol"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.test { useJUnitPlatform(); maxParallelForks = 1; testLogging { events("failed", "skipped") } }
