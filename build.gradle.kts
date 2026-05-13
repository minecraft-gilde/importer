plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = "de.gilde"
version = providers.gradleProperty("releaseVersion").orElse("1.0.4").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.8")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(
            mapOf(
                "version" to project.version
            )
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
