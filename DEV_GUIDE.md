目标：利用 `xland.ioutils:JarCompat:0.1.3` 下的 API，写一个 Java 命令行程序，用于比较一个 Minecraft mod JAR 在两个不同的 Minecraft 环境下的兼容性。

## 参数
* (required) `<program>`: Mod JAR
* (required) `-a / --version-a <version> | -b / --version-b <version>`: 两个Minecraft 版本号
* `--fabric | --neoforge`: 若存在，则包含 Fabric/NeoForge 的库。每个flag都可以指定或不指定。
* `--fabric-override-a | --fabric-override-b | --neoforge-override-a | --neoforge-override-b <version>`: 若存在，则将a/b下的 Fabric Loader/NeoForge 版本覆盖为指定版本。未指定的，默认为对应loader的最新版本（下面有获取方法）。
> 若无 `--fabric` 但指定 `--fabric-override-a`（或类似情况），则报错。
> 如果 `(mcVersion, fabricLoaderVersion, neoForgeVersion)` 在a和b下完全相同，显然这样的比较无意义，退出。

## 上游的 `lib-a` 和 `lib-b` 对应什么？
```pseudocode
const lib = {}
for (const side of ["a", "b"]) {
  let thisLibs = [mcJar[side], ...mcLibs[side]]
  if (fabric) thisLibs = [...thisLibs, ...fabricLoaderLibs[side]] // Fabric Loader 的架构不区分 jar和libs
  if (neoforge) thisLibs = [...thisLibs, neoForgeJar[side], ...neoForgeLibs[side]]
  lib[side] = thisLibs
}
for (const side of ["a", "b"]) {
  const other = side === "a" ? "b" : "a"
  lib[side].removeIf(thisLib => lib[other].any(otherLib => isEquivalent(thisLib, otherLib)))
}
// isEquivalent: 相同 maven 坐标（coords）
```

## 获取不同资源的方式及对应 GAV

### `mcJar`, `mcLibs`
```
type Resource = {url: string; coords: string}
let mcJar: Resource
let mcLibs: Resource[]

let mcVersion: string
const VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
const versionMetaUrl = (() => {
    let manifest: object = await fetch(VERSION_MANIFEST)
    let version: object = manifest["versions"].first((x: object) => x["id"] === mcVersion)
    return version["url"]
})();
const versionMeta: object = await fetch(versionMetaUrl)

mcJar.url = versionMeta["client"]["url"]
mcJar.coords = "com.mojang:minecraft:" + mcVersion

const libraries: array = versionMeta["libraries"]
mcLibs = parseLibraries(libraries)

const parseLibraries = (libraries: array) => libraries.map((entry: object) => {
    let ret = {}
    ret.coords = entry["name"]
    if (entry["downloads"]["artifact"]["url"] is string /*且其上级json结构存在*/) {
      ret.url = entry["downloads"]["artifact"]["url"]
    } else if (entry["url"] is string) {
      const mavenRoot = entry["url"]
      let {group, artifact, version, classifier?, extension?} = parseMavenCoords(ret.coords)  // group:artifact:version[:classifier][@extension]
      ret.url = getMavenArtifactUrl(mavenRoot, {group, artifact, version, classifier, extension})
    } else {
      error
    }
    return ret
})
```

### `fabricLibs`
```
const loaderVersion: string = fabricLoaderOverride ?? (() => {
  const loaders: array = await fetch("https://meta.fabricmc.net/v2/versions/loader/" + mcVersion)
  assert loaders.length !== 0
  return loaders[0]["loader"]["version"]
})()


const loaderMeta: object = await fetch(`https://meta.fabricmc.net/v2/versions/loader/{mcVersion}/{loaderVersion}/profile/json`)

fabricLibs = parseLibraries(loaderMeta["libraries"])
```

### `neoForgeJar`, `neoForgeLibs`
首先定义根据 mcVersion 查询最新 neoForgeVersion 的方法 `export async function latestNeo(mcVersion: string): string`：
```
const API_URL = 'https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge';

/**
 * Function: Convert a Minecraft version to a NeoForge version prefix.
 * Rules:
 *   a) If the first segment is "1": remove "1", take the next two segments,
 *      pad with "0" if missing, return a two-part prefix.
 *      1.21   -> 21.0
 *      1.21.1 -> 21.1
 *   b) If the first segment is not "1": keep all segments, ensure at least three,
 *      pad with "0" if missing, return a three-part prefix.
 *      26.1   -> 26.1.0
 *      26.1.2 -> 26.1.2
 */
function mcVersionToNeoForgePrefix(mcVersion) {
  const parts = mcVersion.split('.');
  if (parts.length < 2) throw new Error('Invalid Minecraft version format: ' + mcVersion);

  if (parts[0] === '1') {
    const major = parts[1];
    const minor = parts[2] ?? '0';
    return `${major}.${minor}`;
  } else {
    const major = parts[0];
    const minor = parts[1];
    const patch = parts[2] ?? '0';
    return `${major}.${minor}.${patch}`;
  }
}

/**
 * Function: Parse a NeoForge version string into a comparable array for sorting.
 * Input examples: "21.4.111-beta", "21.4.111-alpha", "21.4.111"
 * Output example: [21, 4, 111]
 * Alpha and beta suffixes are matched but ignored in comparison.
 * Returns null if the format is not recognized.
 */
function parseVersionForSort(versionStr) {
  const match = versionStr.match(/^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta))?$/);
  if (!match) return null;

  const major = Number(match[1]);
  const minor = Number(match[2]);
  const patch = Number(match[3]);

  return [major, minor, patch];
}

/**
 * Function: Get the latest NeoForge installer jar URL for a given Minecraft version.
 * Steps:
 *   1. Convert the MC version to a NeoForge prefix.
 *   2. Fetch the JSON API and get all NeoForge versions.
 *   3. Filter versions that start with the prefix followed by a dot.
 *   4. Sort them by version number and pick the latest.
 */
export async function latestNeo(mcVersion) {
  const prefix = mcVersionToNeoForgePrefix(mcVersion);

  // 1. Fetch JSON data
  const response = await fetch(API_URL);
  const data = await response.json();
  const allVersions = data.versions ?? [];

  // 2. Filter versions belonging to this Minecraft version
  const candidates = allVersions.filter(v => v.startsWith(prefix + '.'));

  if (candidates.length === 0) {
    throw new Error(`No NeoForge version found for Minecraft ${mcVersion} (prefix ${prefix})`);
  }

  // 3. Sort using parseVersionForSort; only numeric parts are compared
  candidates.sort((a, b) => {
    const pa = parseVersionForSort(a);
    const pb = parseVersionForSort(b);

    if (pa === null && pb === null) return 0;
    if (pa === null) return -1;
    if (pb === null) return 1;

    // Compare major, minor, patch only
    for (let i = 0; i < 3; i++) {
      if (pa[i] !== pb[i]) return pa[i] - pb[i];
    }
    return 0;
  });

  // 4. Pick the latest version (last in ascending order)
  return candidates[candidates.length - 1];
}
```

据此得出 `neoForgeJar` 和 `neoForgeLibs` 的获取方法：
```
let neoForgeJar: Resource
let neoForgeLibs: Resource[]

let neoVersion: string = neoForgeOverride ?? latestNeo(mcVersion)
let neoInstallerJarUrl: string

neoForgeJar.url = getMavenArtifactUrl("https://maven.neoforged.net/releases", {
  group: "net.neoforged", artifact: "neoforge", version: neoVersion, classifier: "universal"
})
neoForgeJar.coords = "net.neoforged:neoforge:" + neoVersion + ":userdev"

neoInstallerJarUrl = getMavenArtifactUrl("https://maven.neoforged.net/releases", {
  group: "net.neoforged", artifact: "neoforge", version: neoVersion, classifier: "installer"
})

const installerArchive: ZipFile = await fetch(neoInstallerJarUrl)
const versionJson: object = installerArchive.read("/version.json")
neoForgeLibs = parseLibraries(versionJson["libraries"])
```

## 额外要求

还需要 README.md 来说明其用途。
