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
