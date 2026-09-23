pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "LiliyaLicensingService"

include(":issuer-core")
include(":issuer-testkit")
include(":issuer-postgres")
include(":issuer-openbao-transit")
include(":issuer-transport-contract")
include(":issuer-http-endpoint")
include(":issuer-s6-acceptance-host")
include(":issuer-runtime")
include(":issuer-request-auth")
include(":issuer-entitlement-spi")
include(":issuer-postgres-entitlement-provider")
include(":issuer-deployment")
include(":issuer-https-listener")
include(":issuer-observability")
include(":issuer-activation")
