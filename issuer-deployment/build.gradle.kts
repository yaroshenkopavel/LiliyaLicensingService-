plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("pro.liliya.licensing.deployment.LicensingDeploymentMainKt")
}

dependencies {
    implementation(project(":issuer-runtime"))
    implementation(project(":issuer-observability"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}
