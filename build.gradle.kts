plugins {
    java
    application
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
    compileOnly("xland.ioutils:JarCompat:0.1.5")
    runtimeOnly("xland.ioutils:JarCompat:0.1.5:all")
    // 测试代码同样只依赖 JarCompat 的公共 API；运行时由上面的 -all 构件提供 ASM

    implementation("com.grack:nanojson:1.10")

    compileOnly("org.jspecify:jspecify:1.0.1")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "xland.ioutils.jarcompat.mods.Main"
    applicationName = "modcompat"
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "ModCompat",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.shadowJar {
    archiveClassifier = "all"
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
}
