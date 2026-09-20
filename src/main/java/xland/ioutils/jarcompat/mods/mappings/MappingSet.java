package xland.ioutils.jarcompat.mods.mappings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.mods.core.ModCompatException;

import net.neoforged.srgutils.IMappingFile;
import net.neoforged.srgutils.INamedMappingFile;

/**
 * 一侧 Minecraft 版本的映射集合：按目标命名空间提供 tiny-remapper 能直接读的 Tiny v2 文件。
 *
 * <h2>为什么两种目标各自只需要一步</h2>
 *
 * <p>两种映射文件都以 {@code official}（{@code 1.x} 上的混淆名）为源，因此：</p>
 *
 * <ul>
 *   <li><b>目标 mojang</b>：Mojang 的 {@code client_mappings} 是 ProGuard 文本，方向为
 *       {@code mojang -> official}（左边可读名、右边混淆名），{@code reverse()} 之后就是
 *       {@code official -> mojang}，一步到位。</li>
 *   <li><b>目标 intermediary</b>：Fabric 发布的 {@code <mc>-intermediary-v2} 本来就是
 *       {@code official -> intermediary}，<b>直接用，不需要和官方映射合成</b>。</li>
 * </ul>
 *
 * <p>曾经考虑过「把两份映射串起来得到 {@code mojang -> intermediary}」，但那条路会踩两个坑：
 * 一是反向串联得到的映射<b>源侧有冲突</b>（实测 {@code Button/b}、{@code Checkbox/b}、
 * {@code CycleButton/b} 会被展开成同一个源），tiny-remapper 直接报
 * {@code Mapping source name conflicts detected}；二是完全没有必要——本项目只会把
 * <b>官方 JAR</b> 重新映射出去，而官方 JAR 的命名空间永远是 {@code official}。
 * {@code mojang -> intermediary} 只有「拿到一份官方名 jar、要输出 intermediary」时才需要，
 * 而那种资源（NeoForge 的 {@code :universal}）本来就是官方名，不需要 remap。</p>
 *
 */
public final class MappingSet {

    private final Path mappingsDir;
    private final Path mojangMappings;
    private final @Nullable Path intermediaryMappings;

    /**
     * @param mappingsDir          合成/转换结果的落盘目录
     * @param mojangMappings       {@code mojang -> official} 的 ProGuard 文件
     * @param intermediaryMappings {@code official -> intermediary} 的 Tiny v2 文件；
     *                             该版本没有 intermediary 时为 {@code null}
     */
    public MappingSet(Path mappingsDir, Path mojangMappings, @Nullable Path intermediaryMappings) {
        this.mappingsDir = Objects.requireNonNull(mappingsDir, "mappingsDir");
        this.mojangMappings = Objects.requireNonNull(mojangMappings, "mojangMappings");
        this.intermediaryMappings = intermediaryMappings;
    }

    /** 该版本是否有 intermediary 映射可用。 */
    public boolean hasIntermediary() {
        return intermediaryMappings != null;
    }

    /**
     * 取得 {@code official -> target} 的 Tiny v2 映射文件。
     *
     * @throws ModCompatException 目标命名空间在该版本不可用，或映射文件读取/写出失败
     */
    public Path tinyFor(MappingNamespace target) {
        return switch (target) {
            case MOJANG -> officialToMojang();
            case INTERMEDIARY -> officialToIntermediary();
            case OFFICIAL -> throw new ModCompatException(
                    "内部错误: official 不是 remap 的目标命名空间（官方 JAR 本来就在这个空间里）");
        };
    }

    /** {@code official -> mojang}：官方映射反向，但先丢掉反向之后有歧义的成员映射。 */
    private Path officialToMojang() {
        Path target = mappingsDir.resolve("official-to-mojang.tiny");
        if (Files.isRegularFile(target)) {
            return target;
        }
        IMappingFile officialToMojang = load(dropAmbiguousMembers(mojangMappings)).reverse();
        return writeTiny(officialToMojang, target,
                MappingNamespace.OFFICIAL.label(), MappingNamespace.MOJANG.label());
    }

    /**
     * 丢掉“反向之后源侧会有歧义”的成员映射，返回过滤后的 ProGuard 文件。
     *
     * <h2>为什么必须做这一步</h2>
     *
     * <p>ProGuard 映射是 {@code mojang -> official}：<b>可读名</b>是源、<b>混淆名</b>是目标，而混淆名在成员
     * 维度上<b>不是全局唯一</b>的——方法 {@code c()V} 会出现在成百上千个不同的类上，每个类各自映射到
     * 一个不同的可读名。于是 {@code reverse()} 之后这些成员变成“同一个源、多个不同目标”，
     * tiny-remapper 直接判定为无法修复并抛 {@code Unfixable conflicts}。</p>
     *
     * <p>实测 1.21.1 的 {@code client_mappings} 里这类冲突遍布 {@code gatherStats}、{@code tick}、
     * {@code fix}、{@code getSerializedName} 等常见方法名。丢掉它们不影响正确性：这些成员在这种映射下
     * <b>本来就没有唯一解</b>，而“不改名”是安全的选择——类名、字段名与绝大多数方法名照常映射。</p>
     *
     * <p>注意<b>类</b>不会冲突：混淆类名是全局唯一的。</p>
     */
    private Path dropAmbiguousMembers(Path proguardFile) {
        Path filtered = mappingsDir.resolve("client_mappings-unambiguous.txt");
        if (Files.isRegularFile(filtered)) {
            return filtered;
        }
        try {
            List<String> lines = Files.readAllLines(proguardFile);

            // 第一遍：按 (混淆成员名 + 描述符) 统计有多少个不同的可读名会映射过来
            Map<String, Set<String>> candidates = new HashMap<>();
            String currentClass = null;
            for (String line : lines) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (line.equals(trimmed) && trimmed.endsWith(":")) {
                    currentClass = trimmed.substring(0, trimmed.length() - 1).trim();
                    continue;
                }
                if (currentClass == null) {
                    continue;
                }
                MemberLine member = MemberLine.parse(trimmed);
                if (member != null) {
                    candidates.computeIfAbsent(member.key(), k -> new HashSet<>()).add(member.readable);
                }
            }

            // 第二遍：丢弃目标有歧义的成员行
            StringBuilder out = new StringBuilder(lines.size() * 40);
            int dropped = 0;
            for (String line : lines) {
                String trimmed = line.strip();
                MemberLine member = trimmed.isEmpty() || trimmed.startsWith("#") ? null : MemberLine.parse(trimmed);
                if (member != null) {
                    Set<String> found = candidates.get(member.key());
                    if (found != null && found.size() > 1) {
                        dropped++;
                        continue;
                    }
                }
                out.append(line).append('\n');
            }

            Files.createDirectories(filtered.getParent());
            Files.writeString(filtered, out.toString());
            this.conflictsDropped = dropped;
            return filtered;
        } catch (IOException e) {
            throw new ModCompatException("无法处理官方映射 " + proguardFile.getFileName()
                    + ": " + e.getMessage(), e);
        }
    }

    /** ProGuard 成员行：{@code <ret> <name>(<args>) -> <obfName>}。 */
    private record MemberLine(String readable, String descriptor, String obfName) {

        /** 反向之后的源键：混淆名 + 描述符（类名不参与，方法表按类分组查找）。 */
        String key() {
            return obfName + descriptor;
        }

        static MemberLine parse(String trimmed) {
            int arrow = trimmed.lastIndexOf(" -> ");
            if (arrow < 0) {
                return null;
            }
            int paren = trimmed.lastIndexOf('(', arrow);
            if (paren < 0) {
                return null;
            }
            int space = trimmed.lastIndexOf(' ', paren);
            if (space < 0) {
                return null;
            }
            int close = trimmed.indexOf(')', paren);
            if (close < 0) {
                return null;
            }
            String readable = trimmed.substring(space + 1, paren);
            if (readable.isEmpty()) {
                return null;
            }
            return new MemberLine(readable, trimmed.substring(paren, close + 1),
                    trimmed.substring(arrow + 4).trim());
        }
    }

    private int conflictsDropped;

    /** 上一次映射构建丢掉的有歧义成员映射条数（0 表示没有冲突）。 */
    public int ambiguousMembersDropped() {
        return conflictsDropped;
    }

    /**
     * {@code official -> intermediary}：Fabric 发布的文件已经就是这个方向，直接返回。
     *
     * <p>这里只做一次轻量校验（确实能按 {@code official -> intermediary} 取到映射），
     * 避免把方向搞反了却直到 remap 完才发现。</p>
     */
    private Path officialToIntermediary() {
        if (intermediaryMappings == null) {
            throw new ModCompatException("该 Minecraft 版本没有 intermediary 映射"
                    + "（intermediary 自 26.1 起才发布）");
        }
        INamedMappingFile named = loadNamed(intermediaryMappings);
        // 注意 srgutils 在这一步是抛 IllegalArgumentException，而不是返回 null
        IMappingFile direct;
        try {
            direct = named.getMap(MappingNamespace.OFFICIAL.label(), MappingNamespace.INTERMEDIARY.label());
        } catch (IllegalArgumentException e) {
            direct = null;
        }
        if (direct == null) {
            throw new ModCompatException("intermediary 映射文件 " + intermediaryMappings.getFileName()
                    + " 里没有 " + MappingNamespace.OFFICIAL.label() + " -> "
                    + MappingNamespace.INTERMEDIARY.label() + " 这一对命名空间（实际有: "
                    + named.getNames() + "）");
        }
        return intermediaryMappings;
    }

    // ------------------------------------------------------------------ 读写

    private static IMappingFile load(Path file) {
        try {
            return IMappingFile.load(file.toFile());
        } catch (IOException e) {
            throw new ModCompatException("无法读取映射文件 " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static INamedMappingFile loadNamed(Path file) {
        try {
            return INamedMappingFile.load(file.toFile());
        } catch (IOException e) {
            throw new ModCompatException("无法读取映射文件 " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 写成 Tiny v2，并把 srgutils 写死的 {@code left}/{@code right} 列名改回真实命名空间名。
     *
     * <p>srgutils 的 TINY writer 不支持指定列名（写出来固定是 {@code left} 与 {@code right}），
     * 而 tiny-remapper 的 {@code createTinyMappingProvider(path, from, to)} 是按列名查找的，
     * 所以必须改写表头。表头列数不变，只替换第 4 列起的名字。</p>
     */
    private static Path writeTiny(IMappingFile mapping, Path target, String src, String dst) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            mapping.write(temp, IMappingFile.Format.TINY, false);

            List<String> lines = Files.readAllLines(temp);
            if (lines.isEmpty()) {
                throw new ModCompatException("映射写出为空: " + target.getFileName());
            }
            String[] header = lines.getFirst().split("\t");
            if (header.length < 5) {
                throw new ModCompatException("映射表头异常（列数 " + header.length + "）: " + lines.getFirst());
            }
            header[3] = src;
            for (int i = 4; i < header.length; i++) {
                header[i] = i == 4 ? dst : dst + (i - 3);
            }
            lines.set(0, String.join("\t", header));
            Files.write(temp, lines);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new ModCompatException("无法写出映射文件 " + target.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
