plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":issuer-core"))
    api(project(":issuer-transport-contract"))
    api(project(":issuer-request-auth"))
    implementation(project(":issuer-activation"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}
