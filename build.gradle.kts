import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.spring") version "2.4.21" apply false
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "8.11.0"
}

val kotlinVersion = "2.4.21"
val coroutinesVersion = "1.11.0"
val jacksonVersion = "2.22.4"
val jacksonAnnotationsVersion = "2.22"
val ulidVersion = "5.2.4"
val eventStoreAdapterVersion = "5.1.0"
val awsSdkVersion = "2.57.11"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")

    group = "com.github.j5ik2o"
    version = "0.0.1-SNAPSHOT"

    extra["kotlin.version"] = kotlinVersion

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Tests load the shared SQL migrations from tools/refinery/migrations.
        workingDir = rootProject.projectDir
    }

    tasks.named<BootJar>("bootJar") {
        enabled = false
    }

    dependencies {
        "implementation"("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    spotless {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.8.0")
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.8.0")
        }
    }
}

project(":infrastructure") {
    dependencies {
        "implementation"("com.github.f4b6a3:ulid-creator:$ulidVersion")
        "implementation"("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    }
}

project(":command:domain") {
    dependencies {
        "implementation"("com.github.f4b6a3:ulid-creator:$ulidVersion")
        "implementation"("com.fasterxml.jackson.core:jackson-annotations:$jacksonAnnotationsVersion")
        "implementation"("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    }
}

project(":command:interface-adaptor-if") {
    dependencies {
        "implementation"(project(":command:domain"))
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    }
}

project(":command:processor") {
    dependencies {
        "implementation"(project(":command:domain"))
        "implementation"(project(":command:interface-adaptor-if"))
        "implementation"("org.springframework:spring-context")
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    }
}

project(":command:interface-adaptor-impl") {
    dependencies {
        "implementation"(project(":infrastructure"))
        "implementation"(project(":command:domain"))
        "implementation"(project(":command:interface-adaptor-if"))
        "implementation"(project(":command:processor"))
        "implementation"("io.github.j5ik2o:event-store-adapter-kotlin:$eventStoreAdapterVersion")
        "implementation"("com.github.f4b6a3:ulid-creator:$ulidVersion")

        "implementation"("org.springframework:spring-context")
        "implementation"("org.springframework.boot:spring-boot-starter-graphql")
        "implementation"("io.projectreactor:reactor-core:3.8.8")
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")

        "implementation"("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
        "implementation"("com.fasterxml.jackson.core:jackson-annotations:$jacksonAnnotationsVersion")
        "implementation"("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
        "implementation"("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.springframework.graphql:spring-graphql-test")
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        "testImplementation"("org.testcontainers:localstack:1.21.4")
        "testImplementation"("org.testcontainers:junit-jupiter:1.21.4")
        "testImplementation"("software.amazon.awssdk:dynamodb:$awsSdkVersion")
    }
}

project(":query:interface-adaptor") {
    dependencies {
        "implementation"("org.springframework:spring-context")
        "implementation"("org.springframework.boot:spring-boot-starter-graphql")
        "implementation"("org.springframework.boot:spring-boot-starter-jdbc")
        "implementation"("io.projectreactor:reactor-core:3.8.8")

        "runtimeOnly"("com.mysql:mysql-connector-j")

        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.springframework.graphql:spring-graphql-test")
        "testImplementation"(project(":command:domain"))
        "testImplementation"("org.testcontainers:mysql:1.21.4")
        "testImplementation"("org.testcontainers:junit-jupiter:1.21.4")
        "testImplementation"("org.flywaydb:flyway-core")
        "testImplementation"("org.flywaydb:flyway-mysql")
        "testImplementation"("com.mysql:mysql-connector-j")
    }
}

project(":rmu") {
    dependencies {
        "implementation"(project(":command:domain"))
        "implementation"(project(":command:interface-adaptor-impl"))
        "implementation"("io.github.j5ik2o:event-store-adapter-kotlin:$eventStoreAdapterVersion")

        "implementation"("org.springframework:spring-context")
        "implementation"("org.springframework.boot:spring-boot-starter-jdbc")
        "implementation"("software.amazon.awssdk:dynamodb:$awsSdkVersion")

        "runtimeOnly"("com.mysql:mysql-connector-j")

        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.testcontainers:mysql:1.21.4")
        "testImplementation"("org.testcontainers:localstack:1.21.4")
        "testImplementation"("org.testcontainers:junit-jupiter:1.21.4")
        "testImplementation"("org.flywaydb:flyway-core")
        "testImplementation"("org.flywaydb:flyway-mysql")
        "testImplementation"("com.mysql:mysql-connector-j")
    }
}

project(":bootstrap") {
    tasks.named<BootJar>("bootJar") {
        enabled = true
        mainClass.set("com.github.j5ik2o.cqrs.es.kotlin.bootstrap.Application")
    }

    dependencies {
        "implementation"(project(":command:domain"))
        "implementation"(project(":command:interface-adaptor-impl"))
        "implementation"(project(":command:processor"))
        "implementation"(project(":query:interface-adaptor"))
        "implementation"(project(":rmu"))
        "implementation"("org.springframework.boot:spring-boot-starter-webflux")
        "implementation"("org.springframework.boot:spring-boot-starter-jdbc")
        "implementation"("commons-cli:commons-cli:1.11.0")
        "implementation"("software.amazon.awssdk:dynamodb:$awsSdkVersion")
        "implementation"("io.github.j5ik2o:event-store-adapter-kotlin:$eventStoreAdapterVersion")
        "implementation"("org.springframework.boot:spring-boot-configuration-processor")
        "runtimeOnly"("com.mysql:mysql-connector-j")
    }
}
