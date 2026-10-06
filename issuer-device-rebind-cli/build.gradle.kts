plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":issuer-core"))
    implementation(project(":issuer-openbao-transit"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    mainClass.set("pro.liliya.licensing.rebindcli.DeviceRebindCodeCliKt")
}

tasks.test {
    useJUnitPlatform()
}
