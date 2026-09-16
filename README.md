# ModCompat

**Minecraft mod 双环境兼容性比较 CLI**：给定一个 mod JAR 与两个 Minecraft 环境（A/B 版本，可分别叠加 Fabric / NeoForge），
判断该 mod 能否**不重新编译**地在两个环境之间运行；不能时逐条给出确定/潜在的不兼容项、在 mod 中的触发位置，
以及预计会抛出的运行期错误（如 `NoSuchMethodError` / `NoClassDefFoundError`）。

* 核心 JAR 比较**完全**交给 [`xland.ioutils:JarCompat:0.1.4`](#10-与-jarcompat-的-api-对应关系) 的公共 API
  （`JarCompat.request()` / `JarCompat.check(CheckRequest)` / `CheckReport`）；本项目只负责按 `DEV_GUIDE.md`
  的规则获取 Minecraft / Fabric / NeoForge 元数据、拼装两侧上游库列表、下载 JAR 并呈现报告，**没有自写核心比较器**。
* 纯静态链接兼容性分析：JarCompat 不加载、不初始化、不执行任何被分析的类。
* JSON 解析使用 `com.grack:nanojson:1.10`（`build.gradle.kts` 中声明），没有自写 JSON parser。
* Java 25 toolchain，Gradle 9.7.1（Kotlin DSL），`com.gradleup.shadow` 打可执行 fat JAR。

---

## 1. 它是怎么工作的

对每一侧（`side ∈ {a, b}`）按 `DEV_GUIDE.md` §2/§3 构建上游库列表：

```text
libs[side] = [mcJar[side], ...mcLibs[side]]          // Mojang 版本清单 → 版本元数据 → client.jar + libraries
if (--fabric)   libs[side] += fabricLibs[side]        // Fabric 不区分 jar 和 libs，全部作为 libs
if (--neoforge) libs[side] += [neoForgeJar[side], ...neoForgeLibs[side]]   // universal jar + installer 内 version.json 的 libraries
```

随后对两侧做同坐标去重/过滤（`isEquivalent` = Maven 坐标 `coords` 相同）：**只保留某一侧独有的资源**，
两侧都有的库（例如两侧版本一致的 gson、asm）不会进入比较，避免把公共噪声算成差异。
最后把 `<program>` 作为 program、`lib-a` / `lib-b` 作为两侧环境依赖交给 `JarCompat.check(...)`。

调用链：

```text
CLI → 元数据解析（Mojang / Fabric / NeoForge）→ lib-a / lib-b（同坐标过滤）
    → 下载到本地缓存（JarCompat 需要 Path）→ JarCompat.check(CheckRequest) → CheckReport → 文本 / JSON 报告
```

---

## 2. 环境要求

| 项目   | 版本                                                                                     |
|--------|------------------------------------------------------------------------------------------|
| JDK    | 25（`build.gradle.kts` 使用 `JavaLanguageVersion.of(25)`）                               |
| Gradle | 通过 `./gradlew` 使用 9.7.1，无需本机安装                                                |
| 网络   | 需要访问 Mojang / Fabric / NeoForge 元数据与 Maven 仓库（见 [§9 已知限制](#9-已知限制)） |

依赖：

```kotlin
compileOnly("xland.ioutils:JarCompat:0.1.4")          // 只用它的公共 API 编译
runtimeOnly("xland.ioutils:JarCompat:0.1.4:all")      // 运行期用自带 ASM 的 fat 构件
implementation("com.grack:nanojson:1.10")             // JSON 解析
testCompileOnly("xland.ioutils:JarCompat:0.1.4")
```

---

## 3. 构建

```bash
./gradlew build        # 编译 + 测试 + 打包（含 shadowJar）
./gradlew test         # 只跑测试（全部离线，不访问网络）
./gradlew shadowJar    # 只打可执行 fat JAR
```

产物：

| 产物                                  | 说明                                                                        |
|---------------------------------------|-----------------------------------------------------------------------------|
| `build/libs/ModCompat-0.1.0-all.jar`  | **自包含可执行 JAR**（含 JarCompat + ASM + nanojson），`java -jar` 直接运行 |
| `build/libs/ModCompat-0.1.0.jar`      | 普通库 JAR；作为依赖使用，运行时 classpath 上需要 JarCompat 与 ASM          |
| `build/distributions/*.zip` / `*.tar` | `application` 插件生成的发行包，内含 `bin/modcompat` 启动脚本               |

---

## 4. 运行

```bash
java -jar build/libs/ModCompat-0.1.0-all.jar <program> -a <版本A> -b <版本B> [选项]
```

最简示例：

```bash
# 原版环境：1.21.1 vs 1.21.4
java -jar build/libs/ModCompat-0.1.0-all.jar mymod.jar -a 1.21.1 -b 1.21.4

# 叠加 Fabric Loader（两侧各自取该 MC 版本下最新的 Loader）
java -jar build/libs/ModCompat-0.1.0-all.jar mymod.jar -a 1.21.1 -b 1.21.4 --fabric

# 叠加 NeoForge，并固定 B 侧的 NeoForge 版本
java -jar build/libs/ModCompat-0.1.0-all.jar mymod.jar -a 1.21.1 -b 1.21.4 --neoforge --neoforge-override-b 21.4.5

# 同时叠加两个 loader，输出 JSON 报告并写文件，存在确定不兼容项时返回退出码 2
java -jar build/libs/ModCompat-0.1.0-all.jar mymod.jar -a 1.21.1 -b 1.21.4 --fabric --neoforge \
    --format json --output report.json --fail-on-error

# 只解析元数据、打印两侧资源列表，不下载不比较（排查环境构建问题很有用）
java -jar build/libs/ModCompat-0.1.0-all.jar mymod.jar -a 1.21.1 -b 1.21.4 --fabric --dry-run
```

`./gradlew run --args="..."` 与发行包里的 `bin/modcompat` 等价。

---

## 5. CLI 参数

### 必填

| 参数                        | 说明                                                                                   |
|-----------------------------|----------------------------------------------------------------------------------------|
| `<program>`                 | 要比较的 Minecraft mod JAR 路径（位置参数，只能给一个；路径以 `-` 开头时用 `--` 分隔） |
| `-a, --version-a <version>` | 环境 A 的 Minecraft 版本号，例如 `1.21.1`                                              |
| `-b, --version-b <version>` | 环境 B 的 Minecraft 版本号，例如 `1.21.4`                                              |

### 环境开关与版本覆盖

| 参数                                                                  | 说明                                                     |
|-----------------------------------------------------------------------|----------------------------------------------------------|
| `--fabric`                                                            | 环境构建时加入 Fabric Loader 相关库                      |
| `--neoforge`                                                          | 环境构建时加入 NeoForge 相关库                           |
| `--fabric-override-a <version>` / `--fabric-override-b <version>`     | 指定该侧 Fabric Loader 版本；**必须同时指定 `--fabric`** |
| `--neoforge-override-a <version>` / `--neoforge-override-b <version>` | 指定该侧 NeoForge 版本；**必须同时指定 `--neoforge`**    |

两个开关可独立指定、可同时指定、也可都不指定。

### 其他选项

| 参数                           | 默认                                                                        | 说明                                                                                          |
|--------------------------------|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `--cache-dir <dir>`            | `$MODCOMPAT_CACHE_DIR` → `$XDG_CACHE_HOME/modcompat` → `~/.cache/modcompat` | JAR 下载缓存目录                                                                              |
| `--format <text\|json>`        | `text`                                                                      | 报告格式                                                                                      |
| `-o, --output <file>`          | 无                                                                          | 额外把报告写入文件（父目录自动创建）                                                          |
| `--fail-on-error`              | 关闭                                                                        | 存在确定不兼容项时返回退出码 2；默认无论结论都返回 0                                          |
| `--dry-run`                    | 关闭                                                                        | 只解析元数据并打印 `lib-a` / `lib-b`（JSON 模式下为 `environmentA/B.coords`），不下载、不比较 |
| `--reachability <all\|entry>`  | `all`                                                                       | 可达性策略；mod 通常没有 `Main-Class`，`entry` 模式会退化成“全部 WARN”                        |
| `--entry <class>`              | 无                                                                          | 入口类（配合 `--reachability entry`）                                                         |
| `--entry-method <name>`        | `main`                                                                      | 入口方法名                                                                                    |
| `-h, --help` / `-V, --version` | —                                                                           | 帮助 / 版本                                                                                   |

选项支持 `--opt value` 与 `--opt=value` 两种写法（短选项同理：`-a 1.21.1`、`-a=1.21.1`）。

---

## 6. `--fabric` / `--neoforge` 与 override 的关系

* 只有指定了 `--fabric`，Fabric Loader 的库才会进入环境；`--fabric-override-*` 只是在启用后**覆盖版本选择**。
  指定了 override 却没有开对应开关 → **报错**（退出码 1），因为该版本不会被使用，静默忽略只会误导。
* 未指定 override 的一侧，取该 loader 在**该侧 Minecraft 版本**下的最新版本：

  | loader   | 版本来源                                                                                                                                                           |
  |----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
  | Fabric   | `https://meta.fabricmc.net/v2/versions/loader/<mcVersion>` → 断言 `loaders` 非空 → `loaders[0].loader.version`                                                     |
  | NeoForge | `https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge` → 按 MC 版本算前缀（`1.21.1 → 21.1`、`26.1 → 26.1.0`）→ 前缀过滤 + 版本排序取最新 |

* “无意义的比较”：若最终 `(mcVersion, fabricLoaderVersion, neoForgeVersion)` 在 A、B 两侧完全相同，
  程序会给出明确提示并提前退出（退出码 1）。**未启用的 loader 版本不参与该判断**，
  所以 `-a 1.21.1 -b 1.21.1 --fabric`（两侧 Loader 版本相同）无意义，
  而 `-a 1.21.1 -b 1.21.1 --fabric --fabric-override-b 0.16.9`（Loader 版本不同）是有意义的比较。

---

## 7. 输出与退出码

* 人类可读信息（环境 A/B、启用的 loader 及版本、`lib-a` / `lib-b` 资源数、过滤统计、下载进度、错误信息）输出到
  **stderr**；报告本体输出到 **stdout**，因此 `--format json` 时 stdout 是干净、可直接管道处理的 JSON。
* `--format text`（默认）输出 ModCompat 头部 + JarCompat 的文本报告 + 结论行。
* `--format json` 输出一个对象，`report` 字段是 JarCompat 报告（`verdict` / `summary` / `errorsByLibBJar` /
  `notes` / `incompatibilities[...]`），外层还带 `environmentA` / `environmentB`（含版本、资源数、坐标列表）。
* 报告含：环境 A/B 的 Minecraft 版本、启用的 loader 及版本、兼容性结论（兼容 / 不兼容 / 无法判定）、
  确定/潜在不兼容项数量、逐条差异（符号、mod 内位置、lib-a/lib-b 来源、原因、预计运行期错误、建议）。

退出码：

| 退出码 | 含义                                                                                                    |
|--------|---------------------------------------------------------------------------------------------------------|
| `0`    | 比较成功完成（无论结论是否兼容；`--fail-on-error` 未开启时“不兼容”也是 0）                              |
| `1`    | 参数错误：缺少必填项、override 与开关冲突、非法取值、mod JAR 不存在或不是可读取的 ZIP/JAR、无意义的比较 |
| `2`    | 发现了确定不兼容项，且指定了 `--fail-on-error`                                                          |
| `3`    | 网络、元数据/JSON 解析、下载、JarCompat 分析失败                                                        |

---

## 8. 项目结构

```text
src/main/java/xland/ioutils/jarcompat/mods/
├── Main.java                     # java -jar 入口
├── ModCompatApp.java             # 主流程：环境构建 → 过滤 → 下载 → JarCompat.check → 输出
├── cli/                          # CliParser / CliOptions / UsageException / ExitCodes
├── core/                         # Resource、MavenCoords（parse + URL 拼接）、LibraryParser、
│                                 # Fetcher/HttpFetcher、MetaClient、JarCache、Zips、ModCompatException
├── meta/                         # MojangMeta、FabricMeta、NeoForgeMeta、NeoForgeVersions
├── env/                          # EnvironmentBuilder、EnvironmentFilter、BuiltEnvironment、EnvironmentVersions

src/test/java/...                 # 42 个测试：坐标解析 / URL 拼接 / 库解析 / 版本排序 / 过滤 / CLI /
                                  # JarCompat JSON 行为 / 离线端到端
```

测试全部离线：`ModCompatAppIntegrationTest` 用假的 `Fetcher` 提供元数据与 JAR，覆盖
“编译两个版本的库 → 编译 mod → 跑完整流程 → 断言报告与退出码”，以及 Fabric/NeoForge 覆盖版本、
无意义比较、下载缓存复用、JSON 输出、错误路径等场景。

---

## 9. 已知限制

* **需要网络**：Mojang 版本清单/元数据、Fabric 元数据、NeoForge 版本 API 与 installer、以及各 Maven 仓库都必须可访问。
  下载体积不小：一个 MC 版本通常包含 100 个左右库（含各平台 natives），首次运行可能达到数百 MB；
  之后同一 URL 的构件会复用缓存（默认 `~/.cache/modcompat`，可用 `--cache-dir` 指定）。
* **元数据没有规则过滤**：`parseLibraries` 按 `DEV_GUIDE.md` 的规则收录 `libraries` 里的全部条目
  （不做 `rules` 的 OS/feature 过滤），因此其它平台的 natives 也会被下载并进入 lib-a/lib-b；
  它们不会与 mod 的引用冲突，只是增加下载量。
* **原版 JAR 是混淆产物**：`versionMeta.downloads.client.url` 指向的官方 client.jar 中 `net.minecraft.*`
  绝大多数是混淆类名；如果 mod 是用 mappings（Yarn / intermediary / Mojang 官方名）编译的，
  它对这些类的引用在两侧都解析不到，会被 JarCompat 记为“外部（未提供）引用”并跳过。
  本工具按 `DEV_GUIDE.md` 规定使用原版 JAR，不做 remap；因此结论主要覆盖
  **库层面**（gson、guava、netty、log4j、asm……）与 JAR 中稳定的具名类。
* **只检查静态链接兼容性**：反射（`Class.forName` / `Method.invoke`）、`ServiceLoader`、动态代理、JNI、
  资源加载、运行期生成/增强字节码都不在覆盖范围内；行为兼容性（语义变化、配置格式）也不检查。
* **可达性默认 `all`**：mod 通常没有 `Main-Class`，JarCompat 的 `entry` 模式无法确定入口，会把所有引用降级为 WARN。
  因此默认 `--reachability all`（program 的所有类/字段/方法都视为入口，任何位置的失效引用都算确定不兼容，
  包括死代码）。想按调用图分析时用 `--reachability entry --entry <类>`。
* **classpath 冲突策略**：使用 JarCompat 默认的 `FIRST_WINS`（`mcJar → mcLibs → fabricLibs → neoForgeJar → neoForgeLibs` 顺序）。
  同一个类被多个库提供时，只按顺序取第一个。
* **JSON 报告的来源**：`--format json` 里 `report` 字段就是 JarCompat 渲染的 JSON 文本
  （`CheckReport.toJson()`，即 `JarCompat.render(report, JSON)`），ModCompat 只是把它解析成对象嵌进外层文档，
  不做字段级改写；若该文本不是合法 JSON，程序会以退出码 3 明确报错。
  （历史说明：0.1.3 的这两个方法因为 nanojson `JsonStringWriter` 未覆写 `toString()` 而返回对象字符串，
  0.1.4 已修复为 `json.done()`；`JarCompatJsonTest` 就是这条行为的回归防线。）
* **JarCompat 版本显示**：`JarCompat.TOOL_VERSION` 读的是包清单里的 `Implementation-Version`，
  shadow fat JAR 会丢掉它（退化成 `0.1.0`），所以 `--version` 显示的是本项目固定的常量 `0.1.4`。
* **缓存不做校验和校验**：缓存命中只检查文件存在且是可读 ZIP；没有比对 sha1。
  若要强制重新下载，删除 `--cache-dir` 下对应文件即可。

---

## 10. 与 JarCompat 的 API 对应关系

| ModCompat                   | JarCompat 0.1.4                                                                                         |
|-----------------------------|---------------------------------------------------------------------------------------------------------|
| `ModCompatApp.check(...)`   | `JarCompat.request()...build()` → `JarCompat.check(CheckRequest)`                                       |
| `CliOptions.format()`       | `ReportFormat.TEXT` / `ReportFormat.JSON`                                                               |
| `CliOptions.reachability()` | `ReachabilityScope.ALL`（默认）/ `ENTRY`                                                                |
| `CheckReport` 的结论与计数  | `verdict()` / `errorCount()` / `warningCount()` / `compatibleReferenceCount()` / `errorsByLibBJar()` 等 |
| `--format text` 的报告      | `CheckReport.toText()`（`JarCompat.render(report, TEXT)`）                                              |
| `--format json` 的报告      | `CheckReport.toJson()`（即 `JarCompat.render(report, JSON)`）的原样嵌入，不做字段级改写                 |

---

## 11. 示例输出（真实运行，节选）

下面是把一个调用 `org.joml.Matrix4f.set3x3(Matrix4f)`（joml 1.10.5 有、1.10.8 已移除）的 mod
放在 `1.21.1 + Fabric` 与 `1.21.4 + Fabric` 之间比较的真实结果：

```text
ModCompat 0.1.0 — Minecraft mod 双环境兼容性比较（JarCompat 0.1.4）
mod JAR   : /path/to/demo-mod.jar
环境 A    : Minecraft 1.21.1 + Fabric Loader 0.19.5
环境 B    : Minecraft 1.21.4 + Fabric Loader 0.19.5
缓存目录  : /home/user/.cache/modcompat
lib-a     : 106 个上游资源 -> 保留 33 个（过滤掉另一侧同坐标 73 个，本侧重复坐标 0 个）
lib-b     : 122 个上游资源 -> 保留 49 个（过滤掉另一侧同坐标 73 个，本侧重复坐标 0 个）
可达性    : all（全量：program 的每个类/字段/方法都视为入口）

JarCompat — JAR Binary Compatibility Checker
可达性：all（全量：program 的所有 class/field/method 均视为入口，不做可达性分析）
检查结果：不兼容
确定不兼容项：1
潜在不兼容项：0
未发现问题的引用：5
已检查引用：6    外部（未提供）引用：0
耗时：2273 ms

不兼容来源统计：
  joml-1.10.8.jar         1 项

================ 确定不兼容项 (ERROR) ================

[ERROR] #1 NoSuchMethodError (描述符变化)
  program 位置:
    demo-mod.jar -> com.example.demo.DemoMod.copy3x3(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f; 第 19 行 — invokevirtual
  引用符号:
    org/joml/Matrix4f.set3x3(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;
  lib-a 来源:
    joml-1.10.5.jar -> public org.joml.Matrix4f org.joml.Matrix4f.set3x3(org.joml.Matrix4f)
  lib-b 来源:
    joml-1.10.8.jar -> (未找到)
  原因:
    B 侧沿着 owner 及其父类/父接口都找不到同名且描述符相同的方法。B 中存在同名方法
    public org.joml.Matrix4f org.joml.Matrix4f.set3x3(org.joml.Matrix4fc)，但描述符不匹配。……
  预计运行时错误:
    java.lang.NoSuchMethodError: 'org.joml.Matrix4f org.joml.Matrix4f.set3x3(org.joml.Matrix4f)'
  建议:
    在 lib-b 中恢复该方法（参数/返回类型必须与 A 完全一致），或提供一个桥接方法（bridge），或重编译 program。
  …（备注：分析规模 program 1 个类，lib-a 32239 个类，lib-b 35515 个类；JarCompat 只做静态链接检查……）

兼容性结论: 不兼容（确定不兼容 1 项，潜在不兼容 0 项，未发现问题的引用 5 项，外部引用 0 项，耗时 2273 ms）
提示: 加 --fail-on-error 可在存在确定不兼容项时返回退出码 2。
```

对应的 JSON（`--format json`，节选；`report` 字段就是 JarCompat 自己渲染的报告）：

```json
{
  "tool": "ModCompat",
  "toolVersion": "0.1.0",
  "jarCompatVersion": "0.1.4",
  "program": "/path/to/demo-mod.jar",
  "cacheDir": "/path/to/cache",
  "reachability": "ALL",
  "environmentA": {
    "minecraft": "1.21.1",
    "fabricLoader": "0.19.5",
    "neoForge": null,
    "resourceCount": 33,
    "removedSharedWithOtherSide": 73,
    "removedDuplicates": 0,
    "coords": ["com.mojang:minecraft:1.21.1", "..."]
  },
  "environmentB": { "minecraft": "1.21.4", "fabricLoader": "0.19.5", "neoForge": null,
                    "resourceCount": 49, "removedSharedWithOtherSide": 73,
                    "removedDuplicates": 0, "coords": ["..."] },
  "report": {
    "tool": "JarCompat",
    "formatVersion": 1,
    "reachability": "ALL",
    "verdict": "INCOMPATIBLE",
    "compatible": false,
    "summary": {
      "errors": 1,
      "warnings": 0,
      "compatibleReferences": 5,
      "checkedReferences": 6,
      "externalReferences": 0,
      "abstractChecks": 1,
      "durationMillis": 2287
    },
    "errorsByLibBJar": { "joml-1.10.8.jar": 1 },
    "warningsByLibBJar": {},
    "notes": ["JarCompat 只做静态链接兼容性检查：不加载、不初始化、不执行任何被分析的类……（共 5 条）"],
    "incompatibilities": [
      {
        "severity": "ERROR",
        "kind": "DESCRIPTOR_CHANGED",
        "kindLabel": "描述符变化",
        "symbol": "org/joml/Matrix4f.set3x3(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;",
        "occurrences": 1,
        "expectedError": "java.lang.NoSuchMethodError",
        "expectedMessage": "java.lang.NoSuchMethodError: 'org.joml.Matrix4f org.joml.Matrix4f.set3x3(org.joml.Matrix4f)'",
        "reason": "B 侧沿着 owner 及其父类/父接口都找不到同名且描述符相同的方法。B 中存在同名方法 public org.joml.Matrix4f org.joml.Matrix4f.set3x3(org.joml.Matrix4fc)，但描述符不匹配。……",
        "suggestion": "在 lib-b 中恢复该方法（参数/返回类型必须与 A 完全一致），或提供一个桥接方法（bridge），或重编译 program。",
        "locations": [
          {
            "programJar": "demo-mod.jar",
            "class": "com.example.demo.DemoMod",
            "method": "copy3x3",
            "descriptor": "(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;",
            "line": 19,
            "detail": "invokevirtual"
          }
        ],
        "libA": {
          "present": true,
          "jar": "joml-1.10.5.jar",
          "declaration": "public org.joml.Matrix4f org.joml.Matrix4f.set3x3(org.joml.Matrix4f)"
        },
        "libB": { "present": false, "jar": "joml-1.10.8.jar", "declaration": null }
      },
      {
        "severity": "INFO",
        "kind": "MULTI_RELEASE",
        "kindLabel": "多版本 JAR",
        "symbol": null,
        "occurrences": 0,
        "expectedError": null,
        "expectedMessage": null,
        "reason": "log4j-core-2.22.1.jar: … 采用 META-INF/versions/9 版本 (运行版本 25)。",
        "suggestion": null,
        "locations": [],
        "libA": null,
        "libB": null
      }
    ]
  }
}
```

---

## 12. 验证情况

* `./gradlew test`：**42 个测试全部通过**，且全部离线（用假的 `Fetcher`），不访问任何网络。
* 离线端到端测试：用 `javax.tools.JavaCompiler` 现场编译“两个版本的库 + 针对 A 版编译的 mod”，
  跑完整流程并断言 `NoSuchMethodError` 结论、退出码、缓存复用、JSON 结构（含 `report` 来自 JarCompat 的
  `kindLabel` 等字段）、无意义比较、错误路径等；`JarCompatJsonTest` 单独守住 `toJson()` 返回合法 JSON 这条依赖行为。
* 真实数据验证：`1.21.1 + Fabric 0.19.5` vs `1.21.4 + Fabric 0.19.5` 首次运行下载 82 个构件
  （约 165 MB；两侧同坐标的 73 个构件被过滤，不产生下载），`lib-a` 32239 个类 / `lib-b` 35515 个类，
  比较耗时约 2.3 s；对调用被移除 API 的 mod 报出确定不兼容（`NoSuchMethodError`），
  `--format json` 的输出可被 `python3 -m json.tool` 正常解析，`--fail-on-error` 返回退出码 2。
