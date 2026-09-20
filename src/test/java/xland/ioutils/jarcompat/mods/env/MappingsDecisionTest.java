package xland.ioutils.jarcompat.mods.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import xland.ioutils.jarcompat.mods.cli.MappingsMode;
import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector.Detection;
import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector.NamespaceKind;
import xland.ioutils.jarcompat.mods.core.Resource;

/**
 * 命名空间决策表的离线测试。
 *
 * <p>{@link MappingsDecision#evaluate(MappingsRequest)} 是纯函数：版本号、loader 开关、mod 的命名空间
 * 全部从参数传入，因此这里能把整张表覆盖完整，不需要网络也不需要真实 JAR。</p>
 */
class MappingsDecisionTest {

    private static Detection detection(NamespaceKind kind) {
        return switch (kind) {
            case INTERMEDIARY -> new Detection(kind, 12, 0, 12, 3, 3, 3, null);
            case MOJANG -> new Detection(kind, 0, 120, 120, 120, 3, 3, null);
            // 认不出来，但确实出现了 Minecraft 类名（例如只有零星的 class_ 命中，或满是 Yarn 名）
            case UNKNOWN -> new Detection(kind, 1, 1, 40, 40, 3, 3, null);
        };
    }

    /** “读到 0 个 Minecraft 类名”的检测结果：命名空间同样是 UNKNOWN，但含义完全不同。 */
    private static Detection noMinecraftReferences() {
        return new Detection(NamespaceKind.UNKNOWN, 0, 0, 0, 0, 3, 3, null);
    }

    private static MappingsRequest request(MappingsMode mode, String a, String b,
                                           boolean fabric, boolean neoForge, NamespaceKind kind) {
        return new MappingsRequest(mode, a, b, fabric, neoForge, detection(kind));
    }

    private static MappingsDecision auto(String a, String b, boolean fabric, boolean neoForge, NamespaceKind kind) {
        return MappingsDecision.evaluate(request(MappingsMode.AUTO, a, b, fabric, neoForge, kind));
    }

    // ------------------------------------------------------------------ 1.x

    @Test
    @DisplayName("1.x + mod 是 mojang：上游本来就在 mojang，无需 remap")
    void mojangModNeedsNoRemap() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", true, false, NamespaceKind.MOJANG);

        assertEquals(MinecraftNamespace.MOJANG, decision.targetNamespace());
        assertFalse(decision.remapNeeded());
        assertTrue(decision.mcLayerConclusive());
        assertFalse(decision.degraded());
        assertNull(decision.variantName());
        assertTrue(decision.warnings().isEmpty(), decision.warnings().toString());
    }

    @Test
    @DisplayName("1.x + Fabric mod + --fabric：对齐到 intermediary 且真的会 remap，结论含 MC 层")
    void intermediaryModWithFabricNeedsRemap() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", true, false, NamespaceKind.INTERMEDIARY);

        assertEquals(MinecraftNamespace.INTERMEDIARY, decision.targetNamespace());
        assertTrue(decision.remapNeeded());
        assertTrue(decision.mcLayerConclusive(), "remapping 引擎已接入，不需要降级");
        assertFalse(decision.degraded());
        assertEquals(MappingsDecision.INTERMEDIARY_VARIANT, decision.variantName());
        assertTrue(decision.warnings().isEmpty(), decision.warnings().toString());
        assertFalse(decision.loaderHeuristic(),
                "--fabric + intermediary mod 是正常组合，不该报“从 loader 推断”");
    }

    @Test
    @DisplayName("1.x + intermediary mod + --neoforge：目标从 loader 推断，并标记为启发式")
    void intermediaryModWithNeoForgeIsHeuristic() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", false, true, NamespaceKind.INTERMEDIARY);

        assertEquals(MinecraftNamespace.MOJANG, decision.targetNamespace());
        assertTrue(decision.remapNeeded());
        assertEquals(MappingsDecision.MOJANG_VARIANT, decision.variantName());
        assertTrue(decision.loaderHeuristic(), "--neoforge + intermediary mod：目标是从 loader 推断的");
        assertTrue(decision.notes().stream().anyMatch(n -> n.contains("按 loader 推断")),
                decision.notes().toString());
    }

    @Test
    @DisplayName("1.x + intermediary mod + 两个 loader：拒绝猜命名空间，只比库层")
    void bothLoadersWithIntermediaryModRefuses() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", true, true, NamespaceKind.INTERMEDIARY);

        assertNull(decision.targetNamespace());
        assertFalse(decision.mcLayerConclusive());
        assertFalse(decision.remapNeeded());
        assertTrue(decision.warnings().stream().anyMatch(w -> w.contains("不能共用同一个目标命名空间")),
                decision.warnings().toString());
    }

    @Test
    @DisplayName("1.x + intermediary mod + 没有 loader：说清楚该加哪个开关")
    void intermediaryModWithoutLoader() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", false, false, NamespaceKind.INTERMEDIARY);

        assertNull(decision.targetNamespace());
        assertFalse(decision.mcLayerConclusive());
        String warning = decision.warnings().getFirst();
        assertTrue(warning.contains("--fabric") && warning.contains("--neoforge"), warning);
    }

    @Test
    @DisplayName("mod 命名空间认不出来：不做任何对齐，但提醒可能是未 remap 的工具链产物")
    void unknownModNamespaceWarns() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", true, false, NamespaceKind.UNKNOWN);

        assertNull(decision.targetNamespace());
        assertFalse(decision.remapNeeded(), "认不出命名空间时不该假装能 remap");
        assertFalse(decision.mcLayerConclusive(), "无法确认是否存在解析不到的 Minecraft 引用");
        assertTrue(decision.degraded(), "本该对齐却做不到，属于降级运行");
        assertTrue(decision.warnings().stream().anyMatch(w -> w.contains("未 remap") && w.contains("Yarn")),
                decision.warnings().toString());
    }

    @Test
    @DisplayName("mod JAR 读不动：不假装它没有 Minecraft 引用")
    void unreadableModJarIsNotClean() {
        MappingsRequest request = new MappingsRequest(MappingsMode.AUTO, "1.21.1", "1.21.4", true, false,
                new Detection(NamespaceKind.UNKNOWN, 0, 0, 0, 0, 0, 0, "zip END header not found"));
        MappingsDecision decision = MappingsDecision.evaluate(request);

        assertFalse(decision.mcLayerConclusive());
        assertTrue(decision.degraded());
        assertTrue(decision.warnings().stream().anyMatch(w -> w.contains("无法读取")),
                decision.warnings().toString());
    }

    @Test
    @DisplayName("mod 没有 Minecraft 引用：两侧都不动，结论仍然完整")
    void noMinecraftReferencesIsClean() {
        MappingsDecision decision = MappingsDecision.evaluate(new MappingsRequest(
                MappingsMode.AUTO, "1.21.1", "1.21.4", false, false, noMinecraftReferences()));

        assertNull(decision.targetNamespace());
        assertFalse(decision.remapNeeded());
        assertTrue(decision.mcLayerConclusive());
        assertFalse(decision.degraded());
        assertTrue(decision.warnings().isEmpty(), decision.warnings().toString());
    }

    // ------------------------------------------------------------------ >= 26.x

    @Test
    @DisplayName("两侧都 >= 26.x：auto 的结果就是 none，不启动 remapper")
    void unobfuscatedAutoIsNoOp() {
        MappingsDecision decision = auto("26.1", "26.1.2", true, false, NamespaceKind.MOJANG);

        assertNull(decision.targetNamespace());
        assertFalse(decision.remapNeeded());
        assertTrue(decision.mcLayerConclusive());
        assertFalse(decision.degraded());
    }

    @Test
    @DisplayName(">= 26.x 上显式 mojang 是恒等操作（短路），intermediary 是参数错误")
    void unobfuscatedExplicitModes() {
        MappingsDecision mojang = MappingsDecision.evaluate(
                request(MappingsMode.MOJANG, "26.1", "26.1.2", true, false, NamespaceKind.MOJANG));
        assertNull(mojang.targetNamespace());
        assertFalse(mojang.remapNeeded());
        assertTrue(mojang.notes().stream().anyMatch(n -> n.contains("恒等操作")), mojang.notes().toString());

        // 两侧都是 1.x 时 intermediary 物理上不存在
        assertThrows(IllegalArgumentException.class, () -> MappingsDecision.evaluate(
                request(MappingsMode.INTERMEDIARY, "1.21.1", "1.21.4", true, false, NamespaceKind.INTERMEDIARY)));
    }

    // ------------------------------------------------------------------ 跨代

    @Test
    @DisplayName("跨代 + mojang mod：只有 mojang 是共有命名空间，无需 remap")
    void crossGenerationMojangMod() {
        MappingsDecision decision = auto("1.21.4", "26.1", true, false, NamespaceKind.MOJANG);

        assertEquals(MinecraftNamespace.MOJANG, decision.targetNamespace());
        assertFalse(decision.remapNeeded());
        assertTrue(decision.mcLayerConclusive());
        assertTrue(decision.notes().stream().anyMatch(n -> n.contains("跨代")), decision.notes().toString());
    }

    @Test
    @DisplayName("跨代 + intermediary mod：1.x 侧的官方 JAR 无法对齐到 mojang，拒绝 Minecraft 层")
    void crossGenerationIntermediaryMod() {
        MappingsDecision decision = auto("1.21.4", "26.1", true, false, NamespaceKind.INTERMEDIARY);

        assertNull(decision.targetNamespace(), "跨代时没有可用的目标命名空间，不该硬塞一个");
        assertFalse(decision.mcLayerConclusive());
        assertFalse(decision.remapNeeded());
        assertTrue(decision.degraded());
        assertTrue(decision.warnings().stream().anyMatch(w -> w.contains("没有合法的映射链")),
                decision.warnings().toString());
        assertTrue(decision.notes().stream().anyMatch(n -> n.contains("无法建立")), decision.notes().toString());
    }

    @Test
    @DisplayName("跨代 + intermediary mod：换成 --neoforge 也一样拒绝（loader 改变不了这个组合）")
    void crossGenerationIntermediaryModNeoForge() {
        MappingsDecision decision = auto("1.21.4", "26.1", false, true, NamespaceKind.INTERMEDIARY);

        assertNull(decision.targetNamespace());
        assertFalse(decision.mcLayerConclusive());
        assertFalse(decision.remapNeeded());
    }

    @Test
    @DisplayName("跨代 + 显式 intermediary：不可达，只比库层（不抛异常）")
    void crossGenerationExplicitIntermediary() {
        MappingsDecision decision = MappingsDecision.evaluate(
                request(MappingsMode.INTERMEDIARY, "1.21.4", "26.1", true, false, NamespaceKind.INTERMEDIARY));

        assertNull(decision.targetNamespace());
        assertFalse(decision.mcLayerConclusive());
        assertTrue(decision.warnings().stream().anyMatch(w -> w.contains("不能用于跨代比较")),
                decision.warnings().toString());
    }

    // ------------------------------------------------------------------ 显式 none

    @Test
    @DisplayName("--mappings none：显式降级，只比库层")
    void explicitNoneDegrades() {
        MappingsDecision decision = MappingsDecision.evaluate(
                request(MappingsMode.NONE, "1.21.1", "1.21.4", true, false, NamespaceKind.INTERMEDIARY));

        assertNull(decision.targetNamespace());
        assertFalse(decision.remapNeeded());
        assertFalse(decision.mcLayerConclusive());
        assertTrue(decision.degraded());
        assertTrue(decision.warnings().stream().anyMatch(w -> w.contains("--mappings none")),
                decision.warnings().toString());
    }

    // ------------------------------------------------------------------ 变体标记

    @Test
    @DisplayName("变体只打在承载 Minecraft 代码的资源上，且不改动基础坐标")
    void taggingOnlyTouchesMinecraftBearingResources() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", true, false, NamespaceKind.INTERMEDIARY);
        List<Resource> resources = List.of(
                new Resource("https://maven/client.jar", "com.mojang:minecraft:1.21.1"),
                new Resource("https://maven/gson.jar", "com.google.code.gson:gson:2.11.0"),
                new Resource("https://maven/loader.jar", "net.fabricmc:fabric-loader:0.19.5"),
                new Resource("https://maven/neo.jar", "net.neoforged:neoforge:21.1.250:universal"),
                new Resource("https://maven/neo-api.jar", "net.neoforged:neoforge:21.1.250:universal-api"));

        List<Resource> tagged = decision.tag(resources);

        assertEquals(MappingsDecision.INTERMEDIARY_VARIANT, tagged.get(0).variant(),
                "官方 client.jar 是混淆产物，需要 remap");
        assertNull(tagged.get(1).variant(), "gson 不承载 Minecraft 代码");
        assertNull(tagged.get(2).variant(), "Fabric Loader 的库不承载 Minecraft 代码");
        assertNull(tagged.get(3).variant(),
                "NeoForge universal JAR 已经是 Mojang 官方名，remap 它是白跑");
        assertNull(tagged.get(4).variant(), "universal-api 是普通库");
        // 基础坐标不变：同坐标过滤必须仍然有效
        for (int i = 0; i < resources.size(); i++) {
            assertEquals(resources.get(i).coords(), tagged.get(i).coords());
            assertEquals(resources.get(i).url(), tagged.get(i).url());
        }
        assertTrue(tagged.get(0).displayName().contains(MappingsDecision.INTERMEDIARY_VARIANT));
    }

    @Test
    @DisplayName("不需要 remap 时不打任何变体")
    void noVariantWhenNothingIsRemapped() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", true, false, NamespaceKind.MOJANG);
        List<Resource> resources = List.of(
                new Resource("https://maven/client.jar", "com.mojang:minecraft:1.21.1"));

        assertEquals(resources, decision.tag(resources));
    }

    @Test
    @DisplayName("tag 不修改入参")
    void taggingDoesNotMutateInput() {
        MappingsDecision decision = auto("1.21.1", "1.21.4", true, false, NamespaceKind.INTERMEDIARY);
        List<Resource> resources = new ArrayList<>();
        resources.add(new Resource("https://maven/client.jar", "com.mojang:minecraft:1.21.1"));

        decision.tag(resources);

        assertNull(resources.getFirst().variant());
    }

    // ------------------------------------------------------------------ 描述

    @Test
    @DisplayName("describe() 把模式、mod 命名空间、目标与结论范围都说清楚")
    void describeIsInformative() {
        String remap = auto("1.21.1", "1.21.4", true, false, NamespaceKind.INTERMEDIARY).describe();
        assertTrue(remap.startsWith("auto -> intermediary"), remap);
        assertTrue(remap.contains("mod: intermediary"), remap);
        assertTrue(remap.contains("需 remap mapped-intermediary"), remap);
        assertTrue(remap.contains("结论含 Minecraft 层"), remap);

        String aligned = auto("1.21.1", "1.21.4", true, false, NamespaceKind.MOJANG).describe();
        assertTrue(aligned.contains("无需 remap"), aligned);
        assertTrue(aligned.contains("结论含 Minecraft 层"), aligned);
    }
}
