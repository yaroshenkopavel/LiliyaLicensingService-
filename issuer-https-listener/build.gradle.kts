plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":issuer-http-endpoint"))
    implementation(project(":issuer-request-auth"))
    implementation(project(":issuer-runtime"))

    testImplementation(project(":issuer-core"))
    testImplementation(project(":issuer-testkit"))
    testImplementation(project(":issuer-openbao-transit"))
    testImplementation(project(":issuer-postgres"))
    testImplementation("org.postgresql:postgresql:42.7.7")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runHttpsAcceptanceHost") {
    group = "verification"
    description = "Runs the S7.6 HTTPS certificate lifecycle acceptance host."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("pro.liliya.licensing.https.ProductionHttpsAcceptanceHostKt")
}

tasks.register<JavaExec>("runS76AuthenticatedHttpsAcceptanceHost") {
    group = "verification"
    description = "Runs the S7.6b authenticated HTTPS cross-repository acceptance host."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("pro.liliya.licensing.https.S76AuthenticatedHttpsAcceptanceHostKt")
}


tasks.register<JavaExec>("runS78StagingHttpsAcceptanceHost") {
    group = "verification"
    description = "Runs the S7.8 staging HTTPS host with PostgreSQL authoritative state."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("pro.liliya.licensing.https.S78StagingHttpsAcceptanceHostKt")
}
