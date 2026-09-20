package xland.ioutils.jarcompat.mods.core;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector.Detection;
import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector.NamespaceKind;

/**
 * {@link ModNamespaceDetector} 的离线测试：用真实的 ZIP 文件承载“class 文件”的字节。
 *
 * <p>伪 class 条目只放入类名字符串本身——检测器就是在常量池字节里找 {@code net/minecraft/...}
 * 形式的类名，不需要合法的 class 结构。</p>
 */
class ModNamespaceDetectorTest {

    @TempDir
    Path work;

    /**
     * 写一个 JAR，条目名 → 条目内容（按 ASCII 直接写入）。
     *
     * <p>内容里的类名之间必须留一个非类名字符作分隔（真实常量池里到处都是 {@code ;}、{@code L}、
     * 长度前缀）。直接首尾相连会让 {@code .../Minecraftnet/minecraft/...} 被当成一个类名，
     * 扫描器会正确地把它读成一个名字——这不是 bug，是测试脚手架必须复现真实字节布局。</p>
     */
    private static Path jarOf(Path target, String entryName, String... contents) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (int i = 0; i < contents.length; i++) {
                zip.putNextEntry(new ZipEntry(contents.length == 1 ? entryName : entryName + i + ".class"));
                zip.write(contents[i].getBytes(US_ASCII));
                zip.closeEntry();
            }
        }
        return target;
    }

    @Test
    @DisplayName("出现 net/minecraft/class_* 就判定为 INTERMEDIARY")
    void detectsIntermediary() throws Exception {
        Path jar = jarOf(work.resolve("fabric.jar"), "a.class",
                "net/minecraft/class_310;net/minecraft/class_2561;net/minecraft/class_2960;");

        Detection detection = ModNamespaceDetector.detect(jar);

        assertEquals(NamespaceKind.INTERMEDIARY, detection.namespace());
        assertEquals(3, detection.intermediaryHits());
        assertEquals(0, detection.mojangEvidence());
        assertTrue(detection.detected());
        assertNull(detection.failure());
    }

    @Test
    @DisplayName("描述符形式 Lnet/minecraft/class_310; 也能被认出来")
    void detectsDescriptorForm() throws Exception {
        Path jar = jarOf(work.resolve("descriptor.jar"), "a.class", "Lnet/minecraft/class_310;");

        Detection detection = ModNamespaceDetector.detect(jar);

        assertEquals(NamespaceKind.INTERMEDIARY, detection.namespace());
        assertEquals(1, detection.intermediaryHits());
    }

    @Test
    @DisplayName("intermediary 只命中一个就判定，压过其它一切信号")
    void singleIntermediaryHitIsEnough() {
        // 官方名里不存在 net/minecraft/class_ 前缀，所以这个信号是决定性的
        assertEquals(NamespaceKind.INTERMEDIARY, ModNamespaceDetector.decide(1, 500));
        assertEquals(NamespaceKind.INTERMEDIARY, ModNamespaceDetector.decide(300, 0));
    }

    @Test
    @DisplayName("正常具名映射（Mojang / Yarn）判定为 MOJANG")
    void detectsMojang() throws Exception {
        Path jar = jarOf(work.resolve("neoforge.jar"), "a.class",
                "net/minecraft/client/Minecraft;net/minecraft/client/gui/GuiGraphics;"
                        + "net/minecraft/world/level/Level;net/minecraft/world/item/ItemStack;"
                        + "net/minecraft/core/Registry;net/minecraft/nbt/CompoundTag;"
                        + "net/minecraft/network/protocol/Packet;net/minecraft/server/MinecraftServer;"
                        + "net/minecraft/resources/ResourceLocation;net/minecraft/util/RandomSource;");

        Detection detection = ModNamespaceDetector.detect(jar);

        assertEquals(9, detection.mojangEvidence(), detection.describe());
        assertEquals(NamespaceKind.MOJANG, detection.namespace(), detection.describe());
    }

    @Test
    @DisplayName("Yarn 名（另一个具名映射）同样判成 MOJANG：本工具只关心“可读 / intermediary / 混淆”")
    void yarnNamesAlsoCountAsMojang() throws Exception {
        // Fabric API 用的就是 Yarn 名（net/minecraft/core/Registry、net/minecraft/tags/TagKey 等），
        // 与 Mojang 名只是用词不同，对“能不能对齐到同一个命名空间”没有区别
        Path jar = jarOf(work.resolve("yarn.jar"), "a.class",
                "net/minecraft/client/MinecraftClient;net/minecraft/client/option/GameOptions;"
                        + "net/minecraft/core/Registry;net/minecraft/tags/TagKey;"
                        + "net/minecraft/resources/ResourceKey;net/minecraft/world/level/Level;"
                        + "net/minecraft/block/Block;net/minecraft/item/Item;"
                        + "net/minecraft/text/Text;net/minecraft/screen/ScreenHandler;");

        Detection detection = ModNamespaceDetector.detect(jar);

        assertEquals(NamespaceKind.MOJANG, detection.namespace(), detection.describe());
    }

    @Test
    @DisplayName("混淆产物保留的可读名一条都不算证据——这是最有价值的回归防线")
    void obfuscatedBootstrapNamesAreNotEvidence() {
        // 实测 1.21.1 client.jar 在 net.minecraft 下保留了 26 个可读名，全部是下列形态。
        // 第一版实现用地标类名清单，结果把这些当成了“这是 Mojang 映射”，把混淆的官方 JAR
        // 判成了 MOJANG。
        for (String name : new String[] {
                "net/minecraft/client/main/Main",
                "net/minecraft/client/main/Main$1",
                "net/minecraft/client/data/Main",
                "net/minecraft/data/Main",
                "net/minecraft/gametest/Main",
                "net/minecraft/obfuscate/DontObfuscate",
                "net/minecraft/server/Main",
                "net/minecraft/server/Main$1",
                "net/minecraft/server/MinecraftServer",
                "net/minecraft/server/MinecraftServer$1",
                "net/minecraft/server/MinecraftServer$a",
                "net/minecraft/server/MinecraftServer$c$1",
                "net/minecraft/client/ClientBrandRetriever",
                "net/minecraft/util/profiling/jfr/event/ChunkGenerationEvent",
                "net/minecraft/util/profiling/jfr/event/NetworkSummaryEvent$a"}) {
            assertFalse(ModNamespaceDetector.isMojangEvidence(name), name + " 不该被当成具名映射的证据");
        }
        // 而正常的映射类名都是证据
        for (String name : new String[] {
                "net/minecraft/client/Minecraft",
                "net/minecraft/client/MinecraftClient",
                "net/minecraft/world/level/Level",
                "net/minecraft/core/Registry",
                "net/minecraft/tags/TagKey"}) {
            assertTrue(ModNamespaceDetector.isMojangEvidence(name), name + " 应当是具名映射的证据");
        }
        // intermediary 名永远不是“具名映射证据”
        assertFalse(ModNamespaceDetector.isMojangEvidence("net/minecraft/class_310"));
    }

    @Test
    @DisplayName("证据不足时不猜：阈值两侧")
    void evidenceThreshold() {
        assertEquals(NamespaceKind.UNKNOWN, ModNamespaceDetector.decide(0, 0));
        assertEquals(NamespaceKind.UNKNOWN, ModNamespaceDetector.decide(0, ModNamespaceDetector.MOJANG_EVIDENCE_THRESHOLD - 1));
        assertEquals(NamespaceKind.MOJANG, ModNamespaceDetector.decide(0, ModNamespaceDetector.MOJANG_EVIDENCE_THRESHOLD));
        assertEquals(NamespaceKind.MOJANG, ModNamespaceDetector.decide(0, 2278));
    }

    @Test
    @DisplayName("简单名可读性判据")
    void readabilityHeuristic() {
        assertTrue(ModNamespaceDetector.isReadableSegment("Minecraft"));
        assertTrue(ModNamespaceDetector.isReadableSegment("client"));     // 小写但够长
        assertTrue(ModNamespaceDetector.isReadableSegment("Level"));
        assertTrue(ModNamespaceDetector.isReadableSegment("Foo$Bar"));    // 内部类
        assertFalse(ModNamespaceDetector.isReadableSegment("dwq"));       // 混淆名
        assertFalse(ModNamespaceDetector.isReadableSegment("a"));
        assertFalse(ModNamespaceDetector.isReadableSegment("abc"));
        assertFalse(ModNamespaceDetector.isReadableSegment("a$1"));
    }

    @Test
    @DisplayName("没有任何 Minecraft 引用时判定为 UNKNOWN（而不是猜一个）")
    void detectsUnknown() throws Exception {
        Path jar = jarOf(work.resolve("plain.jar"), "a.class", "com/example/Mod;org/slf4j/Logger;");

        Detection detection = ModNamespaceDetector.detect(jar);

        assertEquals(NamespaceKind.UNKNOWN, detection.namespace());
        assertNull(detection.failure());
        assertEquals(1, detection.modClasses());
        assertTrue(detection.noMinecraftReferences());
    }

    @Test
    @DisplayName("META-INF 下的 class 条目被忽略")
    void ignoresMetaInf() throws Exception {
        Path jar = jarOf(work.resolve("meta.jar"), "META-INF/versions/9/a.class",
                "net/minecraft/class_310;net/minecraft/class_2561;");

        Detection detection = ModNamespaceDetector.detect(jar);

        assertEquals(NamespaceKind.UNKNOWN, detection.namespace());
        assertEquals(1, detection.modClasses());
        assertEquals(0, detection.scannedClasses());
    }

    @Test
    @DisplayName("读取失败不抛异常，而是记在 failure 上")
    void reportsFailureInsteadOfThrowing() throws Exception {
        Path broken = work.resolve("broken.jar");
        Files.writeString(broken, "这不是一个 ZIP");

        Detection detection = ModNamespaceDetector.detect(broken);

        assertEquals(NamespaceKind.UNKNOWN, detection.namespace());
        assertTrue(detection.failed());
        assertNotNull(detection.failure());
        assertTrue(detection.describe().contains("无法读取"), detection.describe());
    }

    @Test
    @DisplayName("decide 的边界：0 命中、单边命中、阈值两侧")
    void decideBoundaries() {
        assertEquals(NamespaceKind.UNKNOWN, ModNamespaceDetector.decide(0, 0));
        assertEquals(NamespaceKind.INTERMEDIARY, ModNamespaceDetector.decide(1, 0));
        assertEquals(NamespaceKind.MOJANG, ModNamespaceDetector.decide(0, 1000));
        assertEquals(NamespaceKind.INTERMEDIARY, ModNamespaceDetector.decide(5, 1000), "intermediary 优先");
    }

    @Test
    @DisplayName("AsciiSearch：窗口搜索与边界")
    void asciiSearch() {
        byte[] pattern = "net/minecraft/".getBytes(US_ASCII);
        byte[] data = "xxnet/minecraft/class_310yy".getBytes(US_ASCII);
        assertEquals(2, AsciiSearch.indexOf(data, 0, data.length, pattern));
        assertEquals(-1, AsciiSearch.indexOf(data, 0, 5, pattern));
        assertEquals(-1, AsciiSearch.indexOf(data, data.length - 2, data.length, pattern));
        assertEquals(-1, AsciiSearch.indexOf(data, "zzz".getBytes(US_ASCII)));
        assertEquals(-1, AsciiSearch.indexOf("ab".getBytes(US_ASCII), "abc".getBytes(US_ASCII)));
        assertEquals(-1, AsciiSearch.indexOf(data, 5, 4, pattern), "空窗口不越界");
    }

    @Test
    @DisplayName("Minecraft 版本代际判定：主版本 >= 26 起不再混淆")
    void minecraftVersionGenerations() {
        assertTrue(MinecraftVersion.isObfuscated("1.21.1"));
        assertTrue(MinecraftVersion.isObfuscated("1.8.9"));
        assertTrue(MinecraftVersion.isObfuscated("25.9"));
        assertTrue(MinecraftVersion.isUnobfuscated("26.1"));
        assertTrue(MinecraftVersion.isUnobfuscated("26.1.2"));
        assertTrue(MinecraftVersion.isUnobfuscated("27.0"));
        // 解析不出的版本名按“未混淆”处理：remap 一次来路不明的映射比不 remap 更危险
        assertTrue(MinecraftVersion.isUnobfuscated("26w14a"));
        assertTrue(MinecraftVersion.isUnobfuscated("not-a-version"));
        assertEquals(1, MinecraftVersion.major("1.21.1"));
        assertEquals(26, MinecraftVersion.major("26.1"));
        assertNull(MinecraftVersion.major("abc"));
        assertNull(MinecraftVersion.major(""));
    }

    @Test
    @DisplayName("MinecraftLayerProbe：只有一侧提供 Minecraft 类时报不对称")
    void probeDetectsAsymmetry() throws Exception {
        Path withMc = zipOf(work.resolve("with-mc.jar"), "net/minecraft/client/Minecraft.class",
                "net/minecraft/world/level/Level.class");
        Path withoutMc = zipOf(work.resolve("without-mc.jar"), "com/example/Mod.class");

        MinecraftLayerProbe.Result symmetric = MinecraftLayerProbe.probe(List.of(withMc), List.of(withoutMc));
        assertEquals(2, symmetric.classesA());
        assertEquals(0, symmetric.classesB());
        assertFalse(symmetric.conclusive());
        assertEquals(1, symmetric.warnings().size());
        assertTrue(symmetric.warnings().getFirst().contains("环境 B"), symmetric.warnings().getFirst());

        MinecraftLayerProbe.Result both = MinecraftLayerProbe.probe(List.of(withMc), List.of(withMc));
        assertTrue(both.conclusive());
        assertTrue(both.warnings().isEmpty());

        MinecraftLayerProbe.Result neither = MinecraftLayerProbe.probe(List.of(withoutMc), List.of(withoutMc));
        assertTrue(neither.conclusive(), "两侧都没有 Minecraft 类时不算盲区");
    }

    /** 写一个 JAR，**条目名原样使用**（不做 {@code .class} 拼接），供按包名统计的测试使用。 */
    private static Path zipOf(Path target, String... entryNames) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (String entryName : entryNames) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(0xCA);
                zip.closeEntry();
            }
        }
        return target;
    }
}
