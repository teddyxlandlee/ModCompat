package xland.ioutils.jarcompat.mods;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.mods.cli.ExitCodes;
import xland.ioutils.jarcompat.mods.core.Fetcher;
import xland.ioutils.jarcompat.mods.core.MetaClient;
import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector;
import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector.NamespaceKind;
import xland.ioutils.jarcompat.mods.meta.FabricMeta;
import xland.ioutils.jarcompat.mods.meta.MojangMeta;
import xland.ioutils.jarcompat.mods.meta.NeoForgeMeta;

/**
 * 离线端到端测试：用假的 {@link Fetcher} 提供全部元数据与 JAR，完整跑通
 * “CLI → 环境构建 → 同坐标过滤 → 下载缓存 → JarCompat.check → 报告输出”。
 */
class ModCompatAppIntegrationTest {

    @TempDir
    Path work;

    // ------------------------------------------------------------------ 测试脚手架

    /** 预置 URL → 响应的假 HTTP 层；未预置的 URL 一律失败，保证测试不访问网络。 */
    static final class MapFetcher implements Fetcher {
        private final Map<String, byte[]> responses = new LinkedHashMap<>();
        private final List<String> requested = new ArrayList<>();

        MapFetcher put(String url, byte[] body) {
            responses.put(url, body);
            return this;
        }

        MapFetcher put(String url, String body) {
            return put(url, body.getBytes(UTF_8));
        }

        @Override
        public byte[] get(String url) throws IOException {
            requested.add(url);
            byte[] body = responses.get(url);
            if (body == null) {
                throw new IOException("测试未预置的 URL: " + url);
            }
            return body;
        }
    }

    private record Run(int exitCode, String out, String err) {
    }

    private Run run(Fetcher fetcher, String... args) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exit = ModCompatApp.runWith(args, fetcher,
                new PrintStream(outBuffer, true, UTF_8), new PrintStream(errBuffer, true, UTF_8));
        return new Run(exit, outBuffer.toString(UTF_8), errBuffer.toString(UTF_8));
    }

    /** 编译一个类并打成 JAR。{@code classpath} 为 {@code null} 时不传 {@code -classpath}。 */
    private Path compileJar(String name, String dottedClass, String source, @Nullable Path classpath) throws IOException {
        Path sourceFile = work.resolve(name + "/src").resolve(dottedClass.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        Path classes = work.resolve(name + "/classes");
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "测试需要 JDK（javax.tools.JavaCompiler）");
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        if (classpath != null) {
            args.add("-classpath");
            args.add(classpath.toString());
        }
        args.add(sourceFile.toString());
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)),
                "javac 编译失败: " + dottedClass);
        return jar(work.resolve(name + ".jar"), classes);
    }

    private static Path jar(Path target, Path classesDir) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target));
             var walk = Files.walk(classesDir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String entry = classesDir.relativize(file).toString().replace(File.separatorChar, '/');
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return target;
    }

    /** 造一个只含 {@code version.json} 的 NeoForge installer JAR。 */
    private static byte[] installerJar(String versionJson) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(versionJson.getBytes(UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------ 元数据构造

    private static String manifest(String... versions) {
        JsonArray array = new JsonArray();
        for (String version : versions) {
            JsonObject entry = new JsonObject();
            entry.put("id", version);
            entry.put("url", "https://fake/meta/" + version + ".json");
            array.add(entry);
        }
        JsonObject root = new JsonObject();
        root.put("latest", new JsonObject(Map.of("release", versions[versions.length - 1])));
        root.put("versions", array);
        return JsonWriter.string(root);
    }

    /** 构造版本元数据；每个库用 {@code coords|url} 表示，url 为 {@code -} 时改用 {@code entry.url} 形式。 */
    private static String versionMeta(String id, String clientUrl, String... libraries) {
        JsonArray array = new JsonArray();
        for (String library : libraries) {
            String[] parts = library.split("\\|", 2);
            JsonObject entry = new JsonObject();
            entry.put("name", parts[0]);
            if (parts.length == 1 || "-".equals(parts[1])) {
                entry.put("url", "https://fake-maven/");
            } else {
                JsonObject artifact = new JsonObject();
                artifact.put("url", parts[1]);
                JsonObject downloads = new JsonObject();
                downloads.put("artifact", artifact);
                entry.put("downloads", downloads);
            }
            array.add(entry);
        }
        JsonObject client = new JsonObject();
        client.put("url", clientUrl);
        JsonObject downloads = new JsonObject();
        downloads.put("client", client);
        JsonObject root = new JsonObject();
        root.put("id", id);
        root.put("downloads", downloads);
        root.put("libraries", array);
        return JsonWriter.string(root);
    }

    private static String fabricVersions(String loaderVersion) {
        JsonObject loader = new JsonObject();
        loader.put("version", loaderVersion);
        loader.put("stable", true);
        JsonObject entry = new JsonObject();
        entry.put("loader", loader);
        JsonArray array = new JsonArray();
        array.add(entry);
        return JsonWriter.string(array);
    }

    private static String fabricProfile(String loaderVersion) {
        JsonObject library = new JsonObject();
        library.put("name", "net.fabricmc:fabric-loader:" + loaderVersion);
        library.put("url", "https://fake-maven/");
        JsonArray array = new JsonArray();
        array.add(library);
        JsonObject root = new JsonObject();
        root.put("libraries", array);
        return JsonWriter.string(root);
    }

    private static String fabricLoaderListUrl(String mcVersion) {
        return FabricMeta.LOADER_META_BASE + mcVersion;
    }

    private static String fabricProfileUrl(String mcVersion, String loaderVersion) {
        return FabricMeta.LOADER_META_BASE + mcVersion + "/" + loaderVersion + "/profile/json";
    }

    /**
     * NeoForge installer 的下载地址。
     *
     * <p>{@code installerUrl} 只按 Maven 规则拼 URL、不会用到 {@link MetaClient}；
     * 这里给一个一旦被调用就失败的替身，避免为了拿 URL 而构造出 {@code null} 客户端。</p>
     */
    private static String installerUrl(String neoVersion) {
        MetaClient unused = new MetaClient(url -> {
            throw new IOException("测试不应发起网络请求: " + url);
        });
        return new NeoForgeMeta(unused).installerUrl(neoVersion);
    }

    // ------------------------------------------------------------------ 测试用例

    @Test
    @DisplayName("端到端：报告两个环境之间的缺失方法，并复用下载缓存")
    void comparesTwoEnvironments() throws Exception {
        Path libA = compileJar("liba", "lib.a.Service",
                "package lib.a; public class Service { public static String greet() { return \"A\"; } }", null);
        Path libB = compileJar("libb", "lib.a.Service",
                "package lib.a; public class Service { public static String farewell() { return \"B\"; } }", null);
        Path mod = compileJar("mod", "mod.ExampleMod",
                "package mod; public class ExampleMod {"
                        + " public static void run() { System.out.println(lib.a.Service.greet()); } }", libA);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0", "2.0"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar"))
                .put("https://fake/meta/2.0.json", versionMeta("2.0", "https://fake/client-2.0.jar"))
                .put("https://fake/client-1.0.jar", Files.readAllBytes(libA))
                .put("https://fake/client-2.0.jar", Files.readAllBytes(libB));

        Path cache = work.resolve("cache");
        Run first = run(fetcher, mod.toString(), "-a", "1.0", "-b", "2.0", "--cache-dir", cache.toString());

        assertEquals(ExitCodes.OK, first.exitCode(), first.err());
        assertTrue(first.out().contains("环境 A    : Minecraft 1.0"), first.out());
        assertTrue(first.out().contains("环境 B    : Minecraft 2.0"), first.out());
        assertTrue(first.out().contains("NoSuchMethodError"), first.out());
        assertTrue(first.out().contains("兼容性结论: 不兼容"), first.out());
        assertTrue(first.err().contains("新下载 2 个"), first.err());
        assertTrue(Files.isDirectory(cache), "缓存目录应被创建");

        // 第二次运行：命中缓存，不再下载
        Run second = run(fetcher, mod.toString(), "-a", "1.0", "-b", "2.0", "--cache-dir", cache.toString());
        assertEquals(ExitCodes.OK, second.exitCode(), second.err());
        assertTrue(second.err().contains("复用缓存 2 个"), second.err());
    }

    @Test
    @DisplayName("端到端：--fail-on-error 返回退出码 2，JSON 报告可解析")
    void emitsJsonReportAndFailsOnError() throws Exception {
        Path libA = compileJar("json-liba", "lib.a.Service",
                "package lib.a; public class Service { public static String greet() { return \"A\"; } }", null);
        Path libB = compileJar("json-libb", "lib.a.Service",
                "package lib.a; public class Service { }", null);
        Path mod = compileJar("json-mod", "mod.ExampleMod",
                "package mod; public class ExampleMod {"
                        + " public static void run() { System.out.println(lib.a.Service.greet()); } }", libA);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0", "2.0"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar",
                        "com.example:shared:1.0|https://fake/shared-1.0.jar"))
                .put("https://fake/meta/2.0.json", versionMeta("2.0", "https://fake/client-2.0.jar",
                        "com.example:shared:1.0|https://fake/shared-1.0.jar"))
                .put("https://fake/client-1.0.jar", Files.readAllBytes(libA))
                .put("https://fake/client-2.0.jar", Files.readAllBytes(libB))
                .put("https://fake/shared-1.0.jar", Files.readAllBytes(libA));

        Run run = run(fetcher, mod.toString(), "-a", "1.0", "-b", "2.0",
                "--cache-dir", work.resolve("json-cache").toString(),
                "--format", "json", "--fail-on-error");

        assertEquals(ExitCodes.INCOMPATIBLE, run.exitCode(), run.err());
        JsonObject json = JsonParser.object().from(run.out());
        assertEquals("ModCompat", json.getString("tool"));
        assertEquals("1.0", json.getObject("environmentA").getString("minecraft"));
        assertEquals("2.0", json.getObject("environmentB").getString("minecraft"));
        // 两侧坐标相同的库被过滤
        assertEquals(1, json.getObject("environmentA").getInt("removedSharedWithOtherSide"));
        assertEquals(0, json.getObject("environmentA").getInt("removedDuplicates"));
        JsonObject report = json.getObject("report");
        assertEquals("JarCompat", report.getString("tool"));
        assertEquals(1, report.getInt("formatVersion"));
        assertEquals("INCOMPATIBLE", report.getString("verdict"));
        assertTrue(report.getObject("summary").getInt("errors") >= 1, run.out());
        // report 字段是 JarCompat 自己渲染的 JSON（kindLabel 等字段只有它有，ModCompat 不再自行拼装）
        JsonObject item = report.getArray("incompatibilities").getObject(0);
        assertNotNull(item.getString("kindLabel"), run.out());
        assertEquals("ERROR", item.getString("severity"));
    }

    @Test
    @DisplayName("端到端：命名空间决策随环境一起进入 JSON，且没有 Minecraft 引用时结论完整")
    void emitsMappingsDecision() throws Exception {
        Path mod = compileJar("map-mod", "mod.ExampleMod",
                "package mod; public class ExampleMod { public static void main(String[] args) { } }", null);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0", "2.0"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar"))
                .put("https://fake/meta/2.0.json", versionMeta("2.0", "https://fake/client-2.0.jar"))
                .put("https://fake/client-1.0.jar", new byte[] {1})
                .put("https://fake/client-2.0.jar", new byte[] {1});

        Run run = run(fetcher, mod.toString(), "-a", "1.0", "-b", "2.0",
                "--cache-dir", work.resolve("map-cache").toString(), "--dry-run", "--format", "json");

        assertEquals(ExitCodes.OK, run.exitCode(), run.err());
        JsonObject mappings = JsonParser.object().from(run.out()).getObject("mappings");
        assertEquals("auto", mappings.getString("mode"));
        assertEquals("unknown", mappings.getString("modNamespace"), "mod 里没有 Minecraft 类名");
        assertNull(mappings.getString("targetNamespace"));
        assertEquals(false, mappings.getBoolean("remapNeeded"));
        assertEquals(false, mappings.getBoolean("degraded"));
        assertTrue(mappings.getBoolean("mcLayerConclusive"), run.out());
        assertTrue(mappings.getArray("warnings").isEmpty(), run.out());
    }

    @Test
    @DisplayName("端到端：intermediary mod + --fabric 触发 remap 降级，并给出明确警告")
    void degradesWhenRemapIsRequired() throws Exception {
        Path mod = compileJar("intermediary-mod", "mod.ExampleMod", """
                package mod;
                public class ExampleMod {
                    public static final String[] CLASSES = {
                            "net/minecraft/class_310", "net/minecraft/class_2561",
                            "net/minecraft/class_2960", "net/minecraft/class_2378",
                            "net/minecraft/class_1234", "net/minecraft/class_5678"};
                    public static void main(String[] args) { }
                }
                """, null);

        // 先确认测试 fixture 真的会被判成 intermediary，否则后面的断言没有意义
        assertEquals(NamespaceKind.INTERMEDIARY, ModNamespaceDetector.detect(mod).namespace(),
                "测试用的 mod JAR 应当被判成 intermediary");

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0", "1.1"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar"))
                .put("https://fake/meta/1.1.json", versionMeta("1.1", "https://fake/client-1.1.jar"))
                .put(fabricLoaderListUrl("1.0"), fabricVersions("0.19.5"))
                .put(fabricLoaderListUrl("1.1"), fabricVersions("0.19.6"))
                .put(fabricProfileUrl("1.0", "0.19.5"), fabricProfile("0.19.5"))
                .put(fabricProfileUrl("1.1", "0.19.6"), fabricProfile("0.19.6"))
                .put("https://fake/client-1.0.jar", new byte[] {1})
                .put("https://fake/client-1.1.jar", new byte[] {1});

        Run run = run(fetcher, mod.toString(), "-a", "1.0", "-b", "1.1", "--fabric",
                "--cache-dir", work.resolve("remap-cache").toString(), "--dry-run");

        assertEquals(ExitCodes.OK, run.exitCode(), run.err());
        assertTrue(run.out().contains("映射      : auto -> intermediary"), run.out());
        assertTrue(run.out().contains("需 remap mapped-intermediary"), run.out());
        assertTrue(run.out().contains("仅库层结论"), run.out());
        assertTrue(run.err().contains("尚未实现 remapping 引擎"), run.err());
    }

    @Test
    @DisplayName("端到端：intermediary mod 但没有 loader 时，提示该加 --fabric / --neoforge")
    void refusesWithoutLoaderHint() throws Exception {
        Path mod = compileJar("noloader-mod", "mod.ExampleMod", """
                package mod;
                public class ExampleMod {
                    public static final String[] CLASSES = {
                            "net/minecraft/class_310", "net/minecraft/class_2561",
                            "net/minecraft/class_2960", "net/minecraft/class_2378",
                            "net/minecraft/class_1234", "net/minecraft/class_5678"};
                    public static void main(String[] args) { }
                }
                """, null);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0", "1.1"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar"))
                .put("https://fake/meta/1.1.json", versionMeta("1.1", "https://fake/client-1.1.jar"))
                .put("https://fake/client-1.0.jar", new byte[] {1})
                .put("https://fake/client-1.1.jar", new byte[] {1});

        Run run = run(fetcher, mod.toString(), "-a", "1.0", "-b", "1.1",
                "--cache-dir", work.resolve("noloader-cache").toString(), "--dry-run");

        assertEquals(ExitCodes.OK, run.exitCode(), run.err());
        assertTrue(run.out().contains("映射      : auto -> none"), run.out());
        assertTrue(run.err().contains("--fabric"), run.err());
        assertTrue(run.err().contains("--neoforge"), run.err());
    }

    @Test
    @DisplayName("端到端：--mappings none 显式降级；--mappings 非法取值返回退出码 1")
    void explicitMappingsModes() throws Exception {
        Path mod = compileJar("explicit-mod", "mod.ExampleMod",
                "package mod; public class ExampleMod { public static void main(String[] args) { } }", null);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0", "1.1"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar"))
                .put("https://fake/meta/1.1.json", versionMeta("1.1", "https://fake/client-1.1.jar"))
                .put("https://fake/client-1.0.jar", new byte[] {1})
                .put("https://fake/client-1.1.jar", new byte[] {1});

        Run none = run(fetcher, mod.toString(), "-a", "1.0", "-b", "1.1", "--mappings", "none",
                "--cache-dir", work.resolve("none-cache").toString(), "--dry-run", "--format", "json");
        assertEquals(ExitCodes.OK, none.exitCode(), none.err());
        JsonObject mappings = JsonParser.object().from(none.out()).getObject("mappings");
        assertEquals("none", mappings.getString("mode"));
        assertEquals(false, mappings.getBoolean("mcLayerConclusive"));
        assertEquals(true, mappings.getBoolean("degraded"));

        Run invalid = run(fetcher, mod.toString(), "-a", "1.0", "-b", "1.1", "--mappings", "yarn");
        assertEquals(ExitCodes.USAGE, invalid.exitCode(), invalid.out());
        assertTrue(invalid.err().contains("--mappings"), invalid.err());
    }

    @Test
    @DisplayName("端到端：--dry-run 只打印 lib-a/lib-b，不做下载，并过滤掉两侧同坐标资源")
    void dryRunListsFilteredResources() throws Exception {
        Path mod = compileJar("dry-mod", "mod.ExampleMod",
                "package mod; public class ExampleMod { public static void main(String[] args) { } }", null);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0", "1.1"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar",
                        "com.example:shared:1.0|https://fake/shared-1.0.jar",
                        "com.example:a-only:1.0|https://fake/a-only-1.0.jar"))
                .put("https://fake/meta/1.1.json", versionMeta("1.1", "https://fake/client-1.1.jar",
                        "com.example:shared:1.0|https://fake/shared-1.0.jar",
                        "com.example:b-only:1.0|https://fake/b-only-1.0.jar"))
                .put("https://fake/client-1.0.jar", new byte[] {1})
                .put("https://fake/client-1.1.jar", new byte[] {1});

        Run run = run(fetcher, mod.toString(), "-a", "1.0", "-b", "1.1",
                "--cache-dir", work.resolve("dry-cache").toString(), "--dry-run");

        assertEquals(ExitCodes.OK, run.exitCode(), run.err());
        assertTrue(run.out().contains("== lib-a（2 个）=="), run.out());
        assertTrue(run.out().contains("== lib-b（2 个）=="), run.out());
        assertTrue(run.out().contains("com.mojang:minecraft:1.0"), run.out());
        assertTrue(run.out().contains("com.example:a-only:1.0"), run.out());
        assertTrue(run.out().contains("com.example:b-only:1.0"), run.out());
        assertFalse(run.out().contains("com.example:shared:1.0"), run.out());
        assertTrue(run.out().contains("过滤掉另一侧同坐标 1 个"), run.out());
        assertFalse(run.err().contains("下载"), run.err());
        assertFalse(Files.exists(work.resolve("dry-cache")), "dry-run 不应下载任何文件");
    }

    @Test
    @DisplayName("端到端：Fabric + NeoForge 覆盖版本、最新版本选择与 installer 库解析")
    void buildsFabricAndNeoForgeEnvironments() throws Exception {
        Path mod = compileJar("loader-mod", "mod.ExampleMod",
                "package mod; public class ExampleMod { public static void main(String[] args) { } }", null);

        String neoVersions = JsonWriter.string(new JsonObject(Map.of(
                "isSnapshot", false,
                "versions", new JsonArray(List.of("21.1.0", "21.1.250", "21.1.251-beta", "21.4.5", "26.1.0.3-beta")))));

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.21.1", "1.21.4"))
                .put("https://fake/meta/1.21.1.json", versionMeta("1.21.1", "https://fake/client-1.21.1.jar"))
                .put("https://fake/meta/1.21.4.json", versionMeta("1.21.4", "https://fake/client-1.21.4.jar"))
                .put("https://fake/client-1.21.1.jar", new byte[] {1})
                .put("https://fake/client-1.21.4.jar", new byte[] {1})
                .put(fabricLoaderListUrl("1.21.1"), fabricVersions("0.19.5"))
                .put(fabricProfileUrl("1.21.1", "0.19.5"), fabricProfile("0.19.5"))
                .put(fabricProfileUrl("1.21.4", "0.16.9"), fabricProfile("0.16.9"))
                .put(NeoForgeMeta.VERSIONS_URL, neoVersions)
                .put(installerUrl("21.1.251-beta"), installerJar(installerJson("neo-lib-a")))
                .put(installerUrl("21.4.5"), installerJar(installerJson("neo-lib-b")));

        Run run = run(fetcher, mod.toString(), "-a", "1.21.1", "-b", "1.21.4",
                "--cache-dir", work.resolve("loader-cache").toString(),
                "--fabric", "--neoforge",
                "--fabric-override-b", "0.16.9",
                "--neoforge-override-b", "21.4.5",
                "--dry-run");

        assertEquals(ExitCodes.OK, run.exitCode(), run.err());
        // 环境 A：Fabric 未覆盖 → 取最新 0.19.5；NeoForge 未覆盖 → 21.1 前缀下最新的 21.1.251-beta
        assertTrue(run.out().contains("环境 A    : Minecraft 1.21.1 + Fabric Loader 0.19.5 + NeoForge 21.1.251-beta"),
                run.out());
        assertTrue(run.out().contains("环境 B    : Minecraft 1.21.4 + Fabric Loader 0.16.9 + NeoForge 21.4.5"),
                run.out());
        // Fabric profile 使用 entry.url 回退拼接 Maven 地址
        assertTrue(run.out().contains(
                "https://fake-maven/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar"), run.out());
        // NeoForge universal + installer 内的库
        assertTrue(run.out().contains("net.neoforged:neoforge:21.1.251-beta:universal"), run.out());
        assertTrue(run.out().contains("https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                + "21.1.251-beta/neoforge-21.1.251-beta-universal.jar"), run.out());
        assertTrue(run.out().contains("net.neoforged:neo-lib-a:1.0"), run.out());
        assertTrue(run.out().contains("net.neoforged:neo-lib-b:1.0"), run.out());
        assertTrue(run.out().contains("== lib-a（4 个）=="), run.out());
        assertTrue(run.out().contains("== lib-b（4 个）=="), run.out());
    }

    /** 造一份 NeoForge installer 的 version.json，库名可区分两侧。 */
    private static String installerJson(String libraryName) {
        JsonObject artifact = new JsonObject(Map.of("url", "https://fake/" + libraryName + "-1.0.jar"));
        JsonObject downloads = new JsonObject(Map.of("artifact", artifact));
        JsonObject library = new JsonObject(Map.of("name", "net.neoforged:" + libraryName + ":1.0",
                "downloads", downloads));
        return JsonWriter.string(new JsonObject(Map.of("libraries", new JsonArray(List.of(library)))));
    }

    @Test
    @DisplayName("两侧环境完全相同时提示无意义并返回退出码 1")
    void rejectsMeaninglessComparison() throws Exception {
        Path mod = compileJar("same-mod", "mod.ExampleMod",
                "package mod; public class ExampleMod { public static void main(String[] args) { } }", null);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar"))
                .put(fabricLoaderListUrl("1.0"), fabricVersions("0.19.5"))
                .put(fabricProfileUrl("1.0", "0.19.5"), fabricProfile("0.19.5"));

        Run noLoaders = run(fetcher, mod.toString(), "-a", "1.0", "-b", "1.0",
                "--cache-dir", work.resolve("same-cache").toString());
        assertEquals(ExitCodes.USAGE, noLoaders.exitCode(), noLoaders.out());
        assertTrue(noLoaders.err().contains("无意义的比较"), noLoaders.err());

        // 未启用的 loader 不参与判断：两边都启用 fabric 且版本相同 → 仍然无意义
        Run sameFabric = run(fetcher, mod.toString(), "-a", "1.0", "-b", "1.0", "--fabric",
                "--cache-dir", work.resolve("same-cache").toString());
        assertEquals(ExitCodes.USAGE, sameFabric.exitCode(), sameFabric.out());
        assertTrue(sameFabric.err().contains("无意义的比较"), sameFabric.err());
    }

    @Test
    @DisplayName("Minecraft 版本相同但 loader 版本不同时，仍会比较各自差异部分")
    void comparesSameMinecraftWithDifferentLoaders() throws Exception {
        Path mod = compileJar("override-mod", "mod.ExampleMod",
                "package mod; public class ExampleMod { public static void main(String[] args) { } }", null);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar"))
                .put(fabricProfileUrl("1.0", "0.19.5"), fabricProfile("0.19.5"))
                .put(fabricProfileUrl("1.0", "0.16.9"), fabricProfile("0.16.9"));

        Run run = run(fetcher, mod.toString(), "-a", "1.0", "-b", "1.0", "--fabric",
                "--fabric-override-a", "0.19.5", "--fabric-override-b", "0.16.9",
                "--cache-dir", work.resolve("override-cache").toString(), "--dry-run");

        assertEquals(ExitCodes.OK, run.exitCode(), run.err());
        // 原版 JAR 坐标相同 → 两侧都被过滤；各自只剩自己的 Fabric Loader
        assertTrue(run.out().contains("== lib-a（1 个）=="), run.out());
        assertTrue(run.out().contains("== lib-b（1 个）=="), run.out());
        assertTrue(run.out().contains("net.fabricmc:fabric-loader:0.19.5"), run.out());
        assertTrue(run.out().contains("net.fabricmc:fabric-loader:0.16.9"), run.out());
        assertFalse(run.out().contains("com.mojang:minecraft:1.0"), run.out());
    }

    @Test
    @DisplayName("网络/元数据失败返回退出码 3，且错误输出到 stderr")
    void reportsFailuresOnStandardError() throws Exception {
        Path mod = compileJar("fail-mod", "mod.ExampleMod",
                "package mod; public class ExampleMod { public static void main(String[] args) { } }", null);

        MapFetcher fetcher = new MapFetcher()
                .put(MojangMeta.VERSION_MANIFEST_URL, manifest("1.0"))
                .put("https://fake/meta/1.0.json", versionMeta("1.0", "https://fake/client-1.0.jar"));
        Run run = run(fetcher, mod.toString(), "-a", "1.0", "-b", "9.9",
                "--cache-dir", work.resolve("fail-cache").toString());

        assertEquals(ExitCodes.FAILURE, run.exitCode(), run.out());
        assertTrue(run.err().contains("错误: 环境 B"), run.err());
        assertTrue(run.out().isEmpty(), run.out());

        // mod JAR 不存在 → 参数错误
        Run missingJar = run(fetcher, work.resolve("nope.jar").toString(), "-a", "1.0", "-b", "1.0");
        assertEquals(ExitCodes.USAGE, missingJar.exitCode());
        assertTrue(missingJar.err().contains("mod JAR 不存在"), missingJar.err());

        // mod JAR 不是有效 ZIP/JAR → 在下载上游库之前就报参数错误
        Path broken = work.resolve("broken.jar");
        Files.writeString(broken, "这不是一个 JAR");
        Run brokenJar = run(fetcher, broken.toString(), "-a", "1.0", "-b", "1.1",
                "--cache-dir", work.resolve("broken-cache").toString());
        assertEquals(ExitCodes.USAGE, brokenJar.exitCode(), brokenJar.err());
        assertTrue(brokenJar.err().contains("不是可读取的 ZIP/JAR"), brokenJar.err());
        assertFalse(Files.exists(work.resolve("broken-cache")), "不应在参数校验失败后下载任何资源");
    }

    @Test
    @DisplayName("非法参数返回退出码 1 并打印用法提示")
    void reportsUsageErrors() {
        MapFetcher fetcher = new MapFetcher();
        Run run = run(fetcher, "-a", "1.0", "-b", "1.1");
        assertEquals(ExitCodes.USAGE, run.exitCode());
        assertTrue(run.err().contains("参数错误"), run.err());
        assertTrue(run.err().contains("--help"), run.err());

        Run help = run(fetcher, "--help");
        assertEquals(ExitCodes.OK, help.exitCode());
        assertTrue(help.out().contains("用法:"), help.out());
        assertTrue(help.out().contains("--neoforge-override-b"), help.out());

        Run version = run(fetcher, "--version");
        assertEquals(ExitCodes.OK, version.exitCode());
        assertTrue(version.out().contains("ModCompat"), version.out());
        assertTrue(version.out().contains("JarCompat"), version.out());
    }
}
