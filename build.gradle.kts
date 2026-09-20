plugins {
    java
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "xland.ioutils"
version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://maven.hixland.com")
    // tiny-remapper（以及它传递依赖的 mapping-io）只发布在 Fabric 的 Maven 上
    maven("https://maven.fabricmc.net/")
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

    // 映射处理：srgutils 负责读取/合成/转换映射文件，tiny-remapper 负责改写字节码。
    // tiny-remapper 传递依赖 mapping-io（其公共 API 里就出现 MappingTreeView），因此合成
    // “official + intermediary + mojang” 三命名空间映射时也可以直接使用 mapping-io，不需要额外声明。
    // JarCompat 的 -all 构件已把 ASM 重定位到 xland/ioutils/jarcompat/asm/，不会与这里的 ASM 冲突。
    implementation("net.neoforged:srgutils:1.0.10")
    implementation("net.fabricmc:tiny-remapper:0.14.0")
    // 显式抬高 mapping-io：tiny-remapper 传递依赖的是 0.7.1，而合成三命名空间映射需要
    // MappingNsRenamer / MappingDstNsReorder（0.7.1 还没有这些适配器）。
    implementation("net.fabricmc:mapping-io:0.9.1")

    implementation("com.grack:nanojson:1.10")

    compileOnly("org.jspecify:jspecify:1.0.1")
    // 测试源码也使用 @NullMarked/@Nullable；JUnit 只传递来 jspecify 1.0.0，这里显式对齐 main 的 1.0.1
    testCompileOnly("org.jspecify:jspecify:1.0.1")

    // 测试里用 ASM 现场生成合法的 class 文件（tiny-remapper 要解析字节码，不能喂假数据）
    testImplementation("org.ow2.asm:asm:9.9.1")

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
