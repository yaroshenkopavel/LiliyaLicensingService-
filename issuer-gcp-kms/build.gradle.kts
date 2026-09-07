plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":issuer-core"))
    implementation(platform("com.google.cloud:libraries-bom:26.87.0"))
    implementation("com.google.cloud:google-cloud-kms")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}
