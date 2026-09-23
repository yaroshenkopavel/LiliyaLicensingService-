import org.gradle.jvm.application.tasks.CreateStartScripts

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

/*
 * Production provider is intentionally isolated from testRuntimeClasspath.
 *
 * issuer-deployment tests keep their own test-only ServiceLoader provider.
 * The PostgreSQL production provider is added only to the installed
 * application distribution and generated production start scripts.
 */
val productionEntitlementProvider =
    configurations.create("productionEntitlementProvider") {
        isCanBeConsumed = false
        isCanBeResolved = true
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
    implementation(project(":issuer-entitlement-spi"))
    implementation(project(":issuer-activation"))

    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")

    add(
        productionEntitlementProvider.name,
        project(":issuer-postgres-entitlement-provider")
    ) {
        isTransitive = false
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<CreateStartScripts>("startScripts") {
    classpath = classpath?.plus(productionEntitlementProvider)
}

distributions {
    main {
        contents {
            from(productionEntitlementProvider) {
                into("lib")
            }
        }
    }
}

tasks.register<JavaExec>("runS79aProductionServiceAcceptance") {
    group = "verification"
    description =
        "Runs the real production deployment entrypoint with an external test-runtime entitlement provider."
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
