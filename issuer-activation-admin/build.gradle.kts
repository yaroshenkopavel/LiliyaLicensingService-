plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("pro.liliya.licensing.activation.ActivationAdminMainKt")
}

dependencies {
    implementation(project(":issuer-activation"))
    implementation("org.postgresql:postgresql:42.7.7")
}
