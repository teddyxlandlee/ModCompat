package xland.ioutils.jarcompat.mods.mappings;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/**
 * {@link MappingSet} 的离线测试：只需要小小的伪映射文件，不需要网络也不需要真实 JAR。
 *
 * <p>重点是 {@code mojang} 目标那条路上的“歧义成员过滤”——真实 ProGuard 映射里方法混淆名不唯一，
 * 反向之后会变成“同一源、多个目标”，tiny-remapper 会直接判定不可修复。</p>
 */
class MappingSetTest {

    @TempDir
    Path work;

    /** 写一份 ProGuard 文本映射（{@code mojang -> official} 方向）。 */
    private Path proguard(String content) throws IOException {
        Path file = work.resolve("client_mappings.txt");
        Files.writeString(file, content);
        return file;
    }

    /**
     * 造一份 intermediary 的 Tiny v2 文件。
     *
     * <p>真实流程里 {@code MappingFiles} 会把中间件 JAR 里的 {@code mappings/mappings.tiny}
     * 抽出来再交给 {@code MappingSet}，所以这里的夹具直接就是抽出来的那个文件。</p>
     */
    private Path intermediary(String tiny) throws IOException {
        Path file = work.resolve("intermediary-v2.tiny");
        Files.writeString(file, tiny);
        return file;
    }

    private MappingSet set(Path proguard, Path intermediary) {
        return new MappingSet(work.resolve("out"), proguard, intermediary);
    }

    @Test
    @DisplayName("official -> mojang：列名被改写成真实命名空间名，成员映射保留")
    void writesOfficialToMojang() throws Exception {
        Path pg = proguard("""
                # 伪 ProGuard 映射
                net.minecraft.client.Minecraft -> fgo:
                    void tick() -> a
                    net.minecraft.resources.ResourceLocation location -> b
                net.minecraft.resources.ResourceLocation -> akr:
                """);

        Path tiny = set(pg, null).tinyFor(MappingNamespace.MOJANG);

        List<String> lines = Files.readAllLines(tiny);
        // srgutils 的 TINY writer 固定写 left/right，必须被改写
        assertEquals("tiny\t2\t0\tofficial\tmojang", lines.getFirst());
        assertFalse(lines.getFirst().contains("left"), lines.getFirst());
        String body = String.join("\n", lines);
        assertTrue(body.contains("fgo"), body);
        assertTrue(body.contains("net/minecraft/client/Minecraft"), body);
        assertTrue(body.contains("akr"), body);
    }

    @Test
    @DisplayName("反向之后有歧义的成员映射被丢掉，无歧义的保留")
    void dropsAmbiguousMembers() throws Exception {
        // 方法混淆名 "a" 在三个类上分别映射到 tick/fix/getName —— 反向之后
        // "a()V" 会有三个不同的目标名，属于必须丢掉的歧义；
        // 而 "unique" 只有一个来源，应当保留。
        Path pg = proguard("""
                net.minecraft.world.entity.Entity -> efg:
                    void tick() -> a
                net.minecraft.world.level.Level -> bcd:
                    void fix() -> a
                net.minecraft.client.Minecraft -> fgo:
                    void getName() -> a
                    void onlyHere() -> unique
                """);

        MappingSet set = set(pg, null);
        Path tiny = set.tinyFor(MappingNamespace.MOJANG);

        assertTrue(set.ambiguousMembersDropped() >= 3,
                "三条 a()V 映射都该被判为有歧义，实际丢弃 " + set.ambiguousMembersDropped());
        String body = String.join("\n", Files.readAllLines(tiny));
        assertTrue(body.contains("unique"), "无歧义的成员应当保留:\n" + body);
        // tiny v2 里成员行以制表符 + m 开头；被丢掉的三条 a()V 不该再出现
        long methodLines = Files.readAllLines(tiny).stream().filter(l -> l.startsWith("\tm")).count();
        assertEquals(1, methodLines, "只应剩下无歧义的那一条方法映射:\n" + body);
    }

    @Test
    @DisplayName("类名不会冲突：混淆类名是全局唯一的")
    void keepsAllClasses() throws Exception {
        Path pg = proguard("""
                net.minecraft.client.Minecraft -> fgo:
                net.minecraft.resources.ResourceLocation -> akr:
                net.minecraft.world.level.Level -> dcd:
                """);

        Path tiny = set(pg, null).tinyFor(MappingNamespace.MOJANG);

        String body = String.join("\n", Files.readAllLines(tiny));
        for (String name : new String[] {"fgo", "akr", "dcd"}) {
            assertTrue(body.contains(name), "类 " + name + " 应当保留:\n" + body);
        }
    }

    @Test
    @DisplayName("intermediary 目标：直接复用 Fabric 的文件，并校验方向")
    void usesIntermediaryFileDirectly() throws Exception {
        Path pg = proguard("net.minecraft.client.Minecraft -> fgo:\n");
        Path inter = intermediary("""
                tiny\t2\t0\tofficial\tintermediary
                c\tfgo\tnet/minecraft/class_310
                """);

        Path tiny = set(pg, inter).tinyFor(MappingNamespace.INTERMEDIARY);

        assertEquals(inter, tiny, "Fabric 的文件已经是 official -> intermediary，不需要再加工");
        assertTrue(set(pg, inter).hasIntermediary());
    }

    @Test
    @DisplayName("intermediary 文件方向不对时明确报错，而不是到 remap 完才发现")
    void rejectsWrongIntermediaryDirection() throws Exception {
        Path pg = proguard("net.minecraft.client.Minecraft -> fgo:\n");
        // 表头反了：mojang 才是源
        Path inter = intermediary("""
                tiny\t2\t0\tmojang\tintermediary
                c\tnet/minecraft/client/Minecraft\tnet/minecraft/class_310
                """);

        xland.ioutils.jarcompat.mods.core.ModCompatException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        xland.ioutils.jarcompat.mods.core.ModCompatException.class,
                        () -> set(pg, inter).tinyFor(MappingNamespace.INTERMEDIARY));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("official"), e.getMessage());
    }

    @Test
    @DisplayName("没有 intermediary 时请求 intermediary 目标要报错")
    void rejectsIntermediaryWhenMissing() throws Exception {
        Path pg = proguard("net.minecraft.client.Minecraft -> fgo:\n");
        assertFalse(set(pg, null).hasIntermediary());
        org.junit.jupiter.api.Assertions.assertThrows(
                xland.ioutils.jarcompat.mods.core.ModCompatException.class,
                () -> set(pg, null).tinyFor(MappingNamespace.INTERMEDIARY));
    }

    @Test
    @DisplayName("official 不是 remap 目标")
    void rejectsOfficialTarget() throws Exception {
        Path pg = proguard("net.minecraft.client.Minecraft -> fgo:\n");
        org.junit.jupiter.api.Assertions.assertThrows(
                xland.ioutils.jarcompat.mods.core.ModCompatException.class,
                () -> set(pg, null).tinyFor(MappingNamespace.OFFICIAL));
    }
}
