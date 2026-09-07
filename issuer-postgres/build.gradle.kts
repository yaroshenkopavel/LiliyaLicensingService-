plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":issuer-core"))
    implementation("org.postgresql:postgresql:42.7.7")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}
