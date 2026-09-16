plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "xland.ioutils"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://maven.hixland.com")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    compileOnly("xland.ioutils:JarCompat:0.1.3")
    runtimeOnly("xland.ioutils:JarCompat:0.1.3:all")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
}