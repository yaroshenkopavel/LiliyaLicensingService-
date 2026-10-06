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
}

application {
    mainClass.set("pro.liliya.licensing.activationcli.ActivationCodeCliKt")
}

tasks.test {
    useJUnitPlatform()
}
