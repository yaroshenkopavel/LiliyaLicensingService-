plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":issuer-core"))
    implementation(project(":issuer-testkit"))
    implementation(project(":issuer-openbao-transit"))
    implementation(project(":issuer-http-endpoint"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
}

application {
    mainClass.set("pro.liliya.licensing.s6host.S6AcceptanceHostKt")
}
