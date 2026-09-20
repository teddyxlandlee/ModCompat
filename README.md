# ModCompat

**Minecraft mod 双环境兼容性比较 CLI**：给定一个 mod JAR 与两个 Minecraft 环境（A/B 版本，可分别叠加 Fabric / NeoForge），
判断该 mod 能否**不重新编译**地在两个环境之间运行；不能时逐条给出确定/潜在的不兼容项、在 mod 中的触发位置，
以及预计会抛出的运行期错误（如 `NoSuchMethodError` / `NoClassDefFoundError`）。

* 核心 JAR 比较**完全**交给 [`xland.ioutils:JarCompat:0.1.5`](#10-与-jarcompat-的-api-对应关系) 的公共 API
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
| 网络   | 需要访问 Mojang / Fabric / NeoForge 元数据与 Maven 仓库（见 [§10 已知限制](#10-已知限制)） |

依赖：

```kotlin
compileOnly("xland.ioutils:JarCompat:0.1.5")          // 只用它的公共 API 编译
runtimeOnly("xland.ioutils:JarCompat:0.1.5:all")      // 运行期用自带 ASM 的 fat 构件
implementation("com.grack:nanojson:1.10")             // JSON 解析
testCompileOnly("xland.ioutils:JarCompat:0.1.5")
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
| `build/libs/ModCompat-0.1.0-all.jar`  | **自包含可执行 JAR**（含 JarCompat + ASM + nanojson + srgutils + tiny-remapper），`java -jar` 直接运行 |
| `build/libs/ModCompat-0.1.0.jar`      | 普通库 JAR；作为依赖使用，运行时 classpath 上需要上述依赖                    |
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

### 命名空间（映射）策略

| 参数                                           | 默认   | 说明                                                                       |
|------------------------------------------------|--------|----------------------------------------------------------------------------|
| `--mappings <auto\|mojang\|intermediary\|none>` | `auto` | 把两侧上游的 Minecraft 资源对齐到哪个命名空间（`mojang` 的别名：`official`；`intermediary` 的别名：`tiny`） |

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

## 7. 命名空间（映射）处理

### 7.1 为什么需要它

`1.x` 的官方 `client.jar` 里 `net.minecraft.*` 绝大多数是混淆类名（`dwq` 之类）。而 mod 是用
**映射名**编译的：

| 来源                       | 编译时用的名字                        | 例子                              |
|----------------------------|---------------------------------------|-----------------------------------|
| Fabric（`remapJar` 产物）  | intermediary                          | `net/minecraft/class_310`         |
| NeoForge（1.20.2+）        | Mojang 官方名                         | `net/minecraft/client/Minecraft`  |
| 未 remap 的 Loom `-dev`    | Yarn                                  | `net/minecraft/client/MinecraftClient` |
| 原版环境                   | 不用映射（就是混淆名）                | `dwq`                             |

`26.1` 是第一个**不再混淆**的版本，从那以后原版 / Fabric / NeoForge 都不需要映射。

如果两侧的 `net.minecraft.*` 名字不在同一个命名空间里，mod 对它们的引用在两侧都解析不到，
会被 JarCompat 记成“外部引用”后跳过——于是“没发现问题”变成**假阴性**。

### 7.2 基本规则

* **命名空间是一次比较的全局属性**：两侧所有带 Minecraft 的资源都被对齐到**同一个**目标命名空间，
  绝不为两侧各自推导一个（那样比较出来的“不兼容”只是命名空间不同，没有意义）。
* **mod JAR 本身从不 remap**：被对齐的是上游环境。目标命名空间要么等于 mod 自身的命名空间，
  要么就是“不处理”。
* **调制解调上不需要用户干预**：mod 的命名空间由**它自己常量池里的类名**判定，与 `--fabric` /
  `--neoforge` 无关；loader 开关只用来**校验自洽性**。
* **判不出来就不猜**：无法确定命名空间时不做对齐，并在报告里明确写出
  `mcLayerConclusive: false`（“结论只覆盖库层”），而不是给出一份看似正常的假阴性报告。

### 7.3 决策表

| 两侧版本          | mod 的命名空间       | `auto` 的结果                                                                  |
|-------------------|----------------------|--------------------------------------------------------------------------------|
| 都 `>= 26.x`      | 任意                 | 不处理（原版 JAR 本来就未混淆）；`--mappings intermediary` 是参数错误           |
| 都 `1.x`          | Mojang 官方名        | 对齐到 `mojang`（NeoForge 的 universal JAR 本来就在这个空间，无需 remap）       |
| 都 `1.x`          | intermediary         | 唯一启用的 loader 决定目标命名空间；两个 loader 同时启用时**拒绝猜测**，只比库层 |
| 都 `1.x`          | 认不出来             | 不做对齐，只比库层，并提示可能是未 remap 的工具链产物                          |
| 跨代（`1.x` ↔ `>=26.x`） | Mojang 官方名 | 对齐到 `mojang`（唯一的两侧共有命名空间），比较照常进行                        |
| 跨代              | intermediary         | 无法建立映射链：`>= 26.x` 侧没有 intermediary 文件，只比库层                    |

显式 `--mappings` 的语义：

* `mojang` — 在 `>= 26.x` 上是**恒等操作**（短路，不启动 remapper）；在 `1.x` 上把上游对齐到官方名。
* `intermediary` — 只对 `>= 26.1` 有意义。两侧都是 `1.x` 时直接**报参数错误**（退出码 1）；
  跨代时降级为只比库层并给出说明。
* `none` — 显式降级：原样使用官方 JAR，结论只覆盖库层与 JAR 中稳定的具名类。

`auto` 的“自洽性”检查：mod 是 intermediary 却只启用了 `--neoforge`（运行时是 `mojang`）时，
目标命名空间虽然可从 loader 推断，但会额外提醒这是推断结果；同时启用 `--fabric --neoforge`
则不猜，直接降级。

### 7.4 判定是怎么做的

`ModNamespaceDetector` 只读 mod JAR 自己的常量池（不触网、不解压整个 JAR）：

| 信号                                              | 结论           |
|---------------------------------------------------|----------------|
| 出现 `net/minecraft/class_*`                      | `intermediary` |
| 出现**不在混淆保留名单里**的可读 `net/minecraft/*` | `mojang`（含 Yarn 等其它具名映射） |
| 其余                                              | `unknown`      |

**为什么用排除法而不是地标类名清单**：混淆并不会把 `net.minecraft` 下的名字全部打乱——启动器、
数据生成器、JFR 事件类必须保持稳定名字。实测混淆的 `1.21.1 client.jar` 保留了 **26 个**可读类名
（`client.main.Main`、`data.Main`、`obfuscate.DontObfuscate`、`server.Main|MinecraftServer`、
`util.profiling.jfr.event.*`，含内部类）。第一版实现用 `net/minecraft/client/Minecraft` 之类的
地标清单判断，结果**把混淆的官方 client.jar 判成了 MOJANG**。而用“可读名字的数量”同样分不开：
混淆产物 26 个，真实的 Yarn 映射 Fabric API 小模块只有 40 个左右，区间是重叠的。

所以判据是：把混淆器**必然**保留的那几个包挖掉之后，剩下的可读名不可能是混淆名。实测：

| JAR                                                     | 判定           | 证据数 |
|---------------------------------------------------------|----------------|--------|
| `1.20.1 / 1.21.1 / 1.21.10 client.jar`（混淆）           | `unknown`      | 0      |
| loom 的 `minecraft-merged-intermediary`（1.20.2 / 1.21.9） | `intermediary` | 0（`class_*` 命中 7496 / 9895） |
| `26.1.1 / 26.3 client.jar`（未混淆）                     | `mojang`       | 9840 / 10341 |
| `neoforge-21.1.234-universal.jar`                        | `mojang`       | 1016   |
| `fabric-loader-0.19.5.jar`                               | `intermediary` | —      |
| `gson-2.8.9.jar`                                         | `unknown`      | 0（无 MC 引用） |

### 7.5 资源身份：`coords` + `variant`

资源身份拆成两层，因为 `mapped-mojang` 与 `mapped-intermediary` **确实是两份不同的产物**，
但它们又是同一份原始构件的两种加工结果：

* `coords`（基础身份）——例如 `com.mojang:minecraft:1.21.1`。DEV_GUIDE §2 的“同坐标过滤”
  仍然只按它比较，否则同一份 `client.jar` 会因为两侧命名空间不同而被当成两个不同资源。
* `variant`（变体）——`mapped-mojang` / `mapped-intermediary`，参与**缓存键**与报告，不参与过滤。
  只有真正承载 Minecraft 代码的资源才有变体：官方 `client.jar` 与 NeoForge 的 `:universal` JAR；
  纯库（gson、netty）与 Fabric Loader 的库不带变体。
  `variant` 表达的是**目标命名空间**（这份构件应当处于哪个空间），因此即使当前版本还没执行 remap，
  ”需要 remap“时它也会出现在报告里——缓存键因此天然按命名空间分开（`client.jar.mapped-mojang`
  与 `client.jar.mapped-intermediary` 不会互相覆盖），等 remapper 接入后不需要改缓存格式。
  反过来，**不需要 remap 时不会打任何变体**：那时两侧的官方 JAR 本来就在 mod 的命名空间里，
  打上 `mapped-*` 只会让人误以为改写过字节码，也会白占一份缓存。

JSON 报告里两侧环境各带三个逐位对应的数组：`coords`、`variants`（无变体为 `null`）、`displayNames`。

### 7.6 remapping 是怎么执行的

决策说得出目标命名空间，程序就会真的把两侧的官方 `client.jar` 映射过去：

| 步骤 | 做什么 |
|------|--------|
| 1 | 从版本元数据取 `downloads.client_mappings`（ProGuard 文本）；从 Fabric Maven 取 `net.fabricmc:intermediary:<mc>:v2`（Tiny v2） |
| 2 | 目标 `mojang`：ProGuard 文件 `reverse()` 成 `official -> mojang`，写成 Tiny v2（srgutils 的 TINY writer 固定写 `left`/`right` 列名，因此表头会被改写成真实命名空间名） |
| 3 | 目标 `intermediary`：Fabric 的文件本来就是 `official -> intermediary`，直接用 |
| 4 | tiny-remapper 以 `official -> <target>` 重映射 `client.jar`，classpath 喂该侧全部资源（MC 类大量互相继承，继承成员要靠 classpath 解析） |
| 5 | 产物落在带变体后缀的缓存路径（`client.jar.mapped-intermediary`），产物文件本身就是缓存，重复运行直接复用 |

两个容易踩的坑，都已实测确认：

* **不能把两份映射反向串成 `mojang -> intermediary`**。那样得到的映射源侧有冲突（`Button/b`、
  `Checkbox/b`、`CycleButton/b` 会被展开成同一个源），tiny-remapper 直接报
  `Mapping source name conflicts detected`。而且本项目根本不需要这条链——会被 remap 的只有官方 JAR，
  它的命名空间永远是 `official`。
* **NeoForge 的 `:universal` JAR 不需要 remap**。它虽然引用大量 MC 类，但发出来时已经是 Mojang 官方名
  （实测 21.1.234 的 universal JAR 里 SRG 名 `f_*` / `m_*` 出现 0 次），对目标 `mojang` 而言是恒等操作，
  因此不打变体、不白跑一遍。

`--dry-run` 会把命名空间决策一并打印/输出（`mappings` 对象），便于在下载之前确认目标命名空间。

---

## 8. 输出与退出码

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

## 9. 项目结构

```text
src/main/java/xland/ioutils/jarcompat/mods/
├── Main.java                     # java -jar 入口
├── ModCompatApp.java             # 主流程：命名空间决策 → 环境构建 → 过滤 → 下载 → JarCompat.check → 输出
├── cli/                          # CliParser / CliOptions / MappingsMode / UsageException / ExitCodes
├── core/                         # Resource（coords + variant）、MavenCoords（parse + URL 拼接）、
│                                 # LibraryParser、Fetcher/HttpFetcher、MetaClient、JarCache、Zips、
│                                 # MinecraftVersion、ModNamespaceDetector、MinecraftLayerProbe、
│                                 # AsciiSearch、ModCompatException
├── meta/                         # MojangMeta、FabricMeta、NeoForgeMeta、NeoForgeVersions
├── env/                          # EnvironmentBuilder、EnvironmentFilter、BuiltEnvironment、
│                                 # EnvironmentVersions、MappingsRequest、MappingsDecision、MinecraftNamespace
├── mappings/                     # 执行层：MappingFiles（取映射文件）、MappingSet（official -> 目标）、
│                                 # JarRemapper / TinyRemapperEngine（tiny-remapper 引擎）、MappingNamespace

src/test/java/...                 # 88 个测试：坐标解析 / URL 拼接 / 库解析 / 版本排序 / 过滤 / CLI /
                                  # 命名空间检测 / 决策表 / 映射构建（歧义过滤、列名改写）/
                                  # 离线端到端（含真实 remap）/ JarCompat JSON 行为
```

测试全部离线：`ModCompatAppIntegrationTest` 用假的 `Fetcher` 提供元数据与 JAR，覆盖
“编译两个版本的库 → 编译 mod → 跑完整流程 → 断言报告与退出码”，以及 Fabric/NeoForge 覆盖版本、
无意义比较、下载缓存复用、JSON 输出、命名空间决策（含 remap 降级）、错误路径等场景。
`ModNamespaceDetectorTest` / `MappingsDecisionTest` 是纯单元测试，不需要网络也不需要 JDK 编译器。

---

## 10. 已知限制

* **需要网络**：Mojang 版本清单/元数据、Fabric 元数据、NeoForge 版本 API 与 installer、以及各 Maven 仓库都必须可访问。
  下载体积不小：一个 MC 版本通常包含 100 个左右库（含各平台 natives），首次运行可能达到数百 MB；
  之后同一 URL 的构件会复用缓存（默认 `~/.cache/modcompat`，可用 `--cache-dir` 指定）。
* **元数据没有规则过滤**：`parseLibraries` 按 `DEV_GUIDE.md` 的规则收录 `libraries` 里的全部条目
  （不做 `rules` 的 OS/feature 过滤），因此其它平台的 natives 也会被下载并进入 lib-a/lib-b；
  它们不会与 mod 的引用冲突，只是增加下载量。
* **只 remap 官方 `client.jar`**：会被重新映射的只有 `com.mojang:minecraft:<mc>` 这个构件。Fabric/NeoForge
  的 mod 是按各自命名空间编译的，但上游环境里只有原版 JAR 需要对齐；NeoForge 的 `:universal` 与
  Fabric Loader 的库要么已是 Mojang 名、要么不含 MC 类。
* **`>= 26.x` 上不支持 `--mappings intermediary`**：那些版本的官方 JAR 已经是 Mojang 可读名，而本工具
  只有 `official -> intermediary` 一条链，缺 `mojang -> intermediary` 那一跳。该组合会如实降级为只比库层
  （用默认的 `auto` 即可——两侧都 `>= 26.x` 时本来就不需要映射）。
* **remap 依赖官方映射的覆盖面**：ProGuard 映射只覆盖 Mojang 自己发布的类，第三方库（gson、netty、
  lwjgl……）不在其中。这是正确的——它们本来就不该被改名；但这也意味着 remap 后的 JAR 里仍会看到这些库的
  原始类名，属于预期行为。
* **命名空间检测的证据下限**：`ModNamespaceDetector` 用“可读类名 ∈ 混淆保留名单之外”作证据，
  阈值 8 条（见 §7.4）。只引用了个位数 Minecraft 类的极小 mod 会判成 `unknown`，此时程序只比库层
  并给出提示；用 `--mappings mojang` / `--mappings intermediary` 可以显式覆盖。
* **不识别 Yarn 名与 Mojang 名的区别**：两者都是“具名映射”，都会被判成 `mojang` 并按同一个命名空间
  处理。这对本工具是**正确**的（它们与上游官方名的对应关系是同一种），但报告里不会区分
  `net/minecraft/client/MinecraftClient`（Yarn）与 `net/minecraft/client/Minecraft`（Mojang）。
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
  shadow fat JAR 会丢掉它（退化成 `0.1.0`），所以 `--version` 显示的是本项目固定的常量 `0.1.5`。
* **缓存不做校验和校验**：缓存命中只检查文件存在且是可读 ZIP；没有比对 sha1。
  若要强制重新下载，删除 `--cache-dir` 下对应文件即可。

---

## 11. 与 JarCompat 的 API 对应关系

| ModCompat                   | JarCompat 0.1.5                                                                                         |
|-----------------------------|---------------------------------------------------------------------------------------------------------|
| `ModCompatApp.check(...)`   | `JarCompat.request()...build()` → `JarCompat.check(CheckRequest)`                                       |
| `CliOptions.format()`       | `ReportFormat.TEXT` / `ReportFormat.JSON`                                                               |
| `CliOptions.reachability()` | `ReachabilityScope.ALL`（默认）/ `ENTRY`                                                                |
| `CliOptions.mappings()`     | 本工具自己的概念（`MappingsMode`），不来自 JarCompat；判定结果只影响传给 JarCompat 的 JAR 列表与报告字段 |
| `MappingsDecision`          | 纯本工具逻辑：`modNamespace`（自身检测）× 版本代际 × loader → `targetNamespace` / `remapNeeded` / `mcLayerConclusive` |
| `MinecraftLayerProbe`       | 兜底断言：一侧提供了 `net/minecraft/**` 而另一侧没有时，提醒 Minecraft 层结论是盲的                     |
| `CheckReport` 的结论与计数  | `verdict()` / `errorCount()` / `warningCount()` / `compatibleReferenceCount()` / `errorsByLibBJar()` 等 |
| `--format text` 的报告      | `CheckReport.toText()`（`JarCompat.render(report, TEXT)`）                                              |
| `--format json` 的报告      | `CheckReport.toJson()`（即 `JarCompat.render(report, JSON)`）的原样嵌入，不做字段级改写                 |

---

## 12. 示例输出（真实运行，节选）

下面是把一个调用 `org.joml.Matrix4f.set3x3(Matrix4f)`（joml 1.10.5 有、1.10.8 已移除）的 mod
放在 `1.21.1 + Fabric` 与 `1.21.4 + Fabric` 之间比较的真实结果：

```text
ModCompat 0.1.0 — Minecraft mod 双环境兼容性比较（JarCompat 0.1.5）
mod JAR   : /path/to/demo-mod.jar
环境 A    : Minecraft 1.21.1 + Fabric Loader 0.19.5
环境 B    : Minecraft 1.21.4 + Fabric Loader 0.19.5
映射      : auto -> intermediary（mod: intermediary，需 remap mapped-intermediary；结论含 Minecraft 层）
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
  "jarCompatVersion": "0.1.5",
  "program": "/path/to/demo-mod.jar",
  "cacheDir": "/path/to/cache",
  "reachability": "ALL",
  "mappings": {
    "mode": "auto",
    "modNamespace": "intermediary",
    "modNamespaceDetail": "intermediary（命中 214 个 net/minecraft/class_* 类名）",
    "targetNamespace": "intermediary",
    "remapNeeded": true,
    "variant": "mapped-intermediary",
    "degraded": false,
    "loaderHeuristic": false,
    "mcLayerConclusive": true,
    "warnings": [],
    "notes": ["mod 是 intermediary，唯一启用的 loader 是 --fabric（运行时就是 intermediary）：原版 JAR 需要反混淆后重新映射到 intermediary，与 mod 自洽"],
    "minecraftClassesA": 8269,
    "minecraftClassesB": 8312
  },
  "environmentA": {
    "minecraft": "1.21.1",
    "fabricLoader": "0.19.5",
    "neoForge": null,
    "resourceCount": 33,
    "removedSharedWithOtherSide": 73,
    "removedDuplicates": 0,
    "coords": ["com.mojang:minecraft:1.21.1", "..."],
    "variants": ["mapped-intermediary", null],
    "displayNames": ["com.mojang:minecraft:1.21.1 [mapped-intermediary]", "..."]
  },
  "environmentB": { "minecraft": "1.21.4", "fabricLoader": "0.19.5", "neoForge": null,
                    "resourceCount": 49, "removedSharedWithOtherSide": 73,
                    "removedDuplicates": 0, "coords": ["..."],
                    "variants": ["..."], "displayNames": ["..."] },
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

## 13. 验证情况

* `./gradlew test`：**88 个测试全部通过**，且全部离线（用假的 `Fetcher`），不访问任何网络。
* 离线端到端测试：用 `javax.tools.JavaCompiler` 现场编译“两个版本的库 + 针对 A 版编译的 mod”，
  跑完整流程并断言 `NoSuchMethodError` 结论、退出码、缓存复用、JSON 结构（含 `report` 来自 JarCompat 的
  `kindLabel` 等字段）、无意义比较、命名空间决策（含 remap 降级与 `--mappings` 非法取值）、错误路径等；
  `JarCompatJsonTest` 单独守住 `toJson()` 返回合法 JSON 这条依赖行为。
* 真实数据验证：`1.21.1 + Fabric 0.19.5` vs `1.21.4 + Fabric 0.19.5` 首次运行下载 82 个构件
  （约 165 MB；两侧同坐标的 73 个构件被过滤，不产生下载），`lib-a` 32239 个类 / `lib-b` 35515 个类，
  比较耗时约 2.3 s；对调用被移除 API 的 mod 报出确定不兼容（`NoSuchMethodError`），
  `--format json` 的输出可被 `python3 -m json.tool` 正常解析，`--fail-on-error` 返回退出码 2。
* 命名空间检测对着**本机 Gradle 缓存里的真实构件**校准过（不下载任何东西，只读已有 JAR；
  这些数字是 §7.4 表里那些实测值的来源）：

  | 构件                                                    | 期望           | 实测           | 证据数 |
  |---------------------------------------------------------|----------------|----------------|--------|
  | `minecraft_1.20.1 / 1.21.1 / 1.21.10_client.jar`         | `unknown`      | `unknown`      | 0      |
  | loom `minecraft-merged-intermediary`（1.20.2 / 1.21.9）  | `intermediary` | `intermediary` | `class_*` 命中 7496 / 9895 |
  | `minecraft_26.1.1 / 26.3_client.jar`                     | `mojang`       | `mojang`       | 9840 / 10341 |
  | `neoforge-21.1.234 / 26.3.0.1-beta-universal.jar`        | `mojang`       | `mojang`       | 1016 / 1215 |
  | `fabric-loader-0.19.5.jar`                               | `intermediary` | `intermediary` | —      |
  | `gson-2.8.9.jar`                                         | `unknown`      | `unknown`      | 0（无 MC 引用） |

  正是这轮校准推翻了最初“命中地标类名即判 MOJANG”的写法——它把**混淆的** 1.21.1 `client.jar`
  判成了 `mojang`（`ModNamespaceDetectorTest#obfuscatedBootstrapNamesAreNotEvidence` 是它的回归防线）。
  扫描一整份未混淆的 `client.jar`（约 1 万个类）耗时约 0.5 s，mod JAR 通常只需几十毫秒。
* 映射链与 remapping 同样对着**真实数据**验证过（下载 1.21.1 的 `client_mappings` 与
  `intermediary-1.21.1-v2`，把真实的 26 MB `client.jar` 映射出去）：

  | 检查项 | 结果 |
  |--------|------|
  | ProGuard 方向 | 是 `mojang -> official`（`net.minecraft.client.Minecraft -> fgo`），必须 `reverse()` |
  | 反向串联 `mojang -> intermediary` | **不可用**：源侧冲突（`Button/b`、`Checkbox/b`…），tiny-remapper 报 `Mapping source name conflicts detected` |
  | Fabric intermediary 文件 | 已是 `official -> intermediary`，直接用 |
  | `official -> intermediary` 重映射 1.21.1 client.jar | 8269 个类 / 4.1 s，产出 `net/minecraft/class_310.class`（`extends class_4093`、字段 `field_1700`、方法 `method_53465`） |
  | 全量审计（8269 个类、111338 个成员） | 只剩 26 个 `net/minecraft` 类型的名字不符合 intermediary 约定——正是混淆器保留的 `Main` / `MinecraftServer` / `ClientBrandRetriever` / JFR 事件类 |
  | `mojang` 目标（`official -> mojang`） | **首次尝试就炸了**：ProGuard 的方法混淆名不唯一（`c()V` 出现在成百上千个类上），反向之后变成“同一源、多个目标”，tiny-remapper 抛 `Unfixable conflicts` |
  | 解决方式 | 构建映射前丢掉这类有歧义的成员映射。这些成员本来就<b>没有唯一解</b>，“不改名”是安全选择；类名/字段名/绝大多数方法名照常映射（`MappingSetTest` 是它的回归防线） |
  | 端到端真实运行（1.21.1 vs 1.21.4 + Fabric，mod 用 intermediary） | `mcLayerConclusive: true`，`结论范围: 库层 + Minecraft 层`，**411012 条 MC 引用被解析**、1030 项确定不兼容（此前 MC 引用全部落进“外部引用”被跳过） |
  | 端到端真实运行（同一 mod + NeoForge，目标 mojang） | 同样 `结论含 Minecraft 层`，24422 条 MC 引用被解析；两条命名空间链都能跑通 |
