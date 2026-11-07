pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "cqrs-es-example-kotlin"

include(":infrastructure")
include(":command:domain")
include(":command:interface-adaptor-if")
include(":command:interface-adaptor-impl")
include(":command:processor")
include(":query:interface-adaptor")
include(":rmu")
include(":bootstrap")
