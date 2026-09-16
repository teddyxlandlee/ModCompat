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
    // compileOnly 不会进入 testCompileClasspath，而测试代码同样只依赖 JarCompat 的公共 API，
    // 因此必须单独为 test 源集再声明一次；运行时仍由上面的 -all 构件提供 ASM
    testCompileOnly("xland.ioutils:JarCompat:0.1.5")
    runtimeOnly("xland.ioutils:JarCompat:0.1.5:all")

    implementation("com.grack:nanojson:1.10")

    compileOnly("org.jspecify:jspecify:1.0.1")
    // 测试源码也使用 @NullMarked/@Nullable；JUnit 只传递来 jspecify 1.0.0，这里显式对齐 main 的 1.0.1
    testCompileOnly("org.jspecify:jspecify:1.0.1")

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
