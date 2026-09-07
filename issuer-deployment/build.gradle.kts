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
    implementation(project(":issuer-core"))
    implementation(project(":issuer-postgres"))
    implementation(project(":issuer-openbao-transit"))
    implementation(project(":issuer-http-endpoint"))
    implementation(project(":issuer-request-auth"))
    implementation(project(":issuer-https-listener"))
    implementation("org.postgresql:postgresql:42.7.7")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}


tasks.register<JavaExec>("runS79aProductionServiceAcceptance") {
    group = "verification"
    description = "Runs the real production deployment entrypoint with an external test-runtime entitlement provider."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("pro.liliya.licensing.deployment.LicensingDeploymentMainKt")
}

tasks.register<JavaExec>("runS79aSchemaMigration") {
    group = "verification"
    description = "Runs the separate S7.9A admin schema migration helper."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("pro.liliya.licensing.deployment.S79aSchemaMigrationMainKt")
}
