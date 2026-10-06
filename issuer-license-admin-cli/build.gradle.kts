plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":issuer-postgres"))
    implementation("org.postgresql:postgresql:42.7.13")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    mainClass.set("pro.liliya.licensing.admincli.LicenseAdminCliKt")
}

tasks.test {
    useJUnitPlatform()
}
