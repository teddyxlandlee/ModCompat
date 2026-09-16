# 任务：实现 Minecraft Mod JAR 双环境兼容性比较 CLI

你是一名 Java 开发者。请实现一个 Java 命令行程序，使用 `xland.ioutils:JarCompat:0.1.3` 提供的 API，比较一个 Minecraft mod JAR 在两个不同 Minecraft 环境下的兼容性。请生成完整可构建项目，包括源码、构建文件（Maven 或 Gradle）和 `README.md`。

必须使用 `xland.ioutils:JarCompat:0.1.3` 暴露的 API 完成核心 JAR 比较逻辑，不要自行重写等价的核心比较器。若该库 API 不明确，请先根据 Maven 坐标查找其文档/源码，确定正确入口类与调用方式。

## 1. CLI 参数

程序名：`<program>`，必填，表示要比较的 Minecraft mod JAR 路径。

必填：
- `-a, --version-a <version>`：环境 A 的 Minecraft 版本号。
- `-b, --version-b <version>`：环境 B 的 Minecraft 版本号。

可选开关：
- `--fabric`：若指定，则在环境构建中包含 Fabric Loader 相关库。
- `--neoforge`：若指定，则在环境构建中包含 NeoForge 相关库。

上述两个开关可独立指定，也可同时指定，也可都不指定。

可选覆盖版本：
- `--fabric-override-a <version>`
- `--fabric-override-b <version>`
- `--neoforge-override-a <version>`
- `--neoforge-override-b <version>`

规则：
- 若指定了某个 `--fabric-override-*`，但没有指定 `--fabric`，报错。
- 若指定了某个 `--neoforge-override-*`，但没有指定 `--neoforge`，报错。
- 对某一侧未指定 override 时，使用对应 loader 在该侧 Minecraft 版本下的最新版本。
- 若最终 `(mcVersion, fabricLoaderVersion, neoForgeVersion)` 在 A 和 B 下完全相同，则该比较无意义。程序应给出明确提示并提前退出。未启用的 loader 版本不参与“完全相同”判断。

## 2. 构建两侧上游库列表：`lib-a` / `lib-b`

对每一侧 `side ∈ {a, b}`：

```pseudocode
libs[side] = [mcJar[side], ...mcLibs[side]]

if fabric:
    libs[side] += fabricLibs[side]   // Fabric Loader 不区分 jar 和 libs，全部作为 libs 加入

if neoforge:
    libs[side] += [neoForgeJar[side], ...neoForgeLibs[side]]
```

然后对每一侧执行去重/过滤：

```pseudocode
for side in [a, b]:
    other = side == a ? b : a
    libs[side].removeIf(thisLib =>
        libs[other].any(otherLib => isEquivalent(thisLib, otherLib))
    )
```

其中 `isEquivalent` 定义为：两个资源的 Maven 坐标 `coords` 相同。

最终得到 `lib-a` 和 `lib-b`。调用 `JarCompat` API 时，将 `<program>` 作为待比较 mod JAR，将 `lib-a` 与 `lib-b` 作为两侧上游库/环境依赖，输出兼容性比较结果。

## 3. 资源获取与 Maven 坐标

定义通用类型：

```text
Resource = { url: string, coords: string }
```

### 3.1 Minecraft 原版 JAR 与库

```text
VERSION_MANIFEST = https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
```

步骤：
1. 拉取 `version_manifest_v2.json`。
2. 在 `versions` 中找到 `id == mcVersion` 的条目。
3. 拉取其 `url` 对应的 version meta JSON。
4. `mcJar.url = versionMeta.client.url`
5. `mcJar.coords = "com.mojang:minecraft:" + mcVersion`
6. `mcLibs = parseLibraries(versionMeta.libraries)`

实现 `parseLibraries(libraries)`：
- 对每个 `entry`：
    - `ret.coords = entry.name`
    - 如果 `entry.downloads.artifact.url` 存在且为字符串，则 `ret.url = entry.downloads.artifact.url`
    - 否则如果 `entry.url` 为字符串，则：
        - 用 `parseMavenCoords(ret.coords)` 解析 `group:artifact:version[:classifier][@extension]`
        - 用 `getMavenArtifactUrl(entry.url, {group, artifact, version, classifier, extension})` 拼接下载 URL
    - 否则报错。

`parseMavenCoords` 需支持：
- `group:artifact:version`
- `group:artifact:version:classifier`
- `group:artifact:version@extension`
- `group:artifact:version:classifier@extension`

`getMavenArtifactUrl` 按 Maven 仓库规则拼接 URL。

### 3.2 Fabric

若启用 `--fabric`：

```text
loaderVersion = fabricOverride
    ?? fetch("https://meta.fabricmc.net/v2/versions/loader/" + mcVersion)
        -> 断言 loaders 非空 -> loaders[0].loader.version
```

然后：

```text
loaderMeta = fetch(
  "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json"
)

fabricLibs = parseLibraries(loaderMeta.libraries)
```

### 3.3 NeoForge

若启用 `--neoforge`：

```text
neoVersion = neoForgeOverride ?? latestNeo(mcVersion)
```

实现 `latestNeo(mcVersion)`：

API：

```text
https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge
```

Minecraft 版本到 NeoForge 前缀规则：
- 若第一段是 `"1"`：去掉 `"1"`，取接下来两段，不足补 `"0"`，返回两段前缀。
    - `1.21` -> `21.0`
    - `1.21.1` -> `21.1`
- 若第一段不是 `"1"`：保留所有段，至少三段，不足补 `"0"`，返回三段前缀。
    - `26.1` -> `26.1.0`
    - `26.1.2` -> `26.1.2`

版本排序解析：
- 匹配 `^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta))?$`
- 只比较 major、minor、patch 数字部分，忽略 alpha/beta 后缀。
- 无法识别的版本返回 null，排序时放在合适位置。

`latestNeo` 步骤：
1. 计算前缀 `prefix`。
2. 拉取版本 JSON，取 `data.versions`。
3. 过滤 `v.startsWith(prefix + ".")` 的候选版本。
4. 按数字部分升序排序。
5. 返回最后一个，即最新版本。
6. 若没有候选，报错。

NeoForge 资源：

```text
neoForgeJar.url = getMavenArtifactUrl(
  "https://maven.neoforged.net/releases",
  {
    group: "net.neoforged",
    artifact: "neoforge",
    version: neoVersion,
    classifier: "universal"
  }
)

neoForgeJar.coords = "net.neoforged:neoforge:" + neoVersion + ":universal"

neoInstallerJarUrl = getMavenArtifactUrl(
  "https://maven.neoforged.net/releases",
  {
    group: "net.neoforged",
    artifact: "neoforge",
    version: neoVersion,
    classifier: "installer"
  }
)
```

下载 installer JAR，作为 ZIP 读取 `/version.json`：

```text
versionJson = installerArchive.read("/version.json")
neoForgeLibs = parseLibraries(versionJson.libraries)
```

## 4. 比较与输出

- 使用 `JarCompat:0.1.3` API 比较 `<program>`。
- 将 `lib-a` 与 `lib-b` 作为两侧上游库/环境依赖传入。
- 输出应清晰包含：
    - 环境 A/B 的 Minecraft 版本；
    - 启用的 loader 及版本；
    - 最终兼容性结论；
    - JarCompat 返回的差异/缺失/冲突等关键信息。
- 错误信息输出到 stderr。
- 成功比较返回退出码 0；参数错误、网络错误、解析错误、无意义比较等返回非 0，并在 README 中说明。

## 5. 使用库

- JSON parser: 使用`build.gradle.kts`中声明的`nanojson`库用于JSON parsing，禁止自写JSON parser。

## 6. README.md

必须提供 `README.md`，说明：
- 项目用途；
- 构建方式；
- 运行方式；
- 所有 CLI 参数及示例；
- `--fabric` / `--neoforge` 与 override 的关系；
- 依赖 `xland.ioutils:JarCompat:0.1.3`；
- 已知限制，例如需要网络访问 Mojang/Fabric/NeoForge 元数据与 Maven 仓库。

## 7. 验收标准

- CLI 参数解析正确，必填项和冲突项校验完整。
- 能正确获取 Minecraft、Fabric、NeoForge 资源并解析 Maven 坐标。
- 能按规则构建 `lib-a` 和 `lib-b`，并按相同 Maven 坐标过滤。
- 正确调用 `xland.ioutils:JarCompat:0.1.3` API，而不是自写核心比较器。
- 输出可读，错误处理明确。
- 项目可构建、可运行，且附带完整 README。

请生成完整 Java 项目，不要只给伪代码或片段。
