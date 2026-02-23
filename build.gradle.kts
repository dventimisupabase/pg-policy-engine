plugins {
    kotlin("jvm") version "2.1.20"
    antlr
    application
}

group = "com.pgpe"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // ANTLR
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")

    // Z3 SMT solver
    implementation("tools.aqua:z3-turnkey:4.13.0")

    // PostgreSQL
    implementation("org.postgresql:postgresql:42.7.4")
    // CLI
    implementation("com.github.ajalt.clikt:clikt:5.0.2")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

kotlin {
    jvmToolchain(21)
}

tasks.generateGrammarSource {
    maxHeapSize = "64m"
    arguments = arguments + listOf("-visitor", "-long-messages", "-package", "com.pgpe.parser.generated")
    outputDirectory = file("${project.layout.buildDirectory.get()}/generated-src/antlr/main/com/pgpe/parser/generated")
}

tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.compileTestKotlin {
    dependsOn(tasks.generateTestGrammarSource)
}

sourceSets {
    main {
        java {
            srcDir("${project.layout.buildDirectory.get()}/generated-src/antlr/main")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xmx512m")
}

application {
    mainClass.set("com.pgpe.cli.MainKt")
}
