package xland.ioutils.jarcompat.mods.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;

/**
 * 从一个 JAR 自身的常量池指纹判断它是在哪个 Minecraft 命名空间下编译的。
 *
 * <p>不看 loader 参数、也不看文件名，只看 JAR 里的类引用长什么样——这是唯一能区分
 * “NeoForge mod 用 Mojang 官方名”“Fabric mod 用 intermediary”的依据：</p>
 *
 * <ul>
 *   <li>{@code net/minecraft/class_310} → {@link NamespaceKind#INTERMEDIARY}（Fabric 运行时的
 *       命名空间）。这个前缀在官方名里不可能出现，所以命中一个就够。</li>
 *   <li>任何<b>不在混淆产物保留名单里</b>的可读 {@code net/minecraft/*} 类名 →
 *       {@link NamespaceKind#MOJANG}（Mojang 官方名，以及 Yarn 这类具名映射）。</li>
 * </ul>
 *
 * <h2>为什么不能用“命中某个地标类”来判断</h2>
 *
 * <p>第一版实现用一份 {@code net/minecraft/client/Minecraft} 之类的地标类名清单来判断，结果是
 * <b>1.21.1 的官方混淆 client.jar 也被判成 MOJANG</b>。原因是混淆并不是把 {@code net.minecraft}
 * 下的名字全部打乱：启动器、数据生成器与 JFR 事件类必须保持稳定名字，否则外部工具没法用。
 * 实测 1.21.1 client.jar 在 {@code net.minecraft} 下保留了 <b>26 个</b>可读类名，
 * 全部落在 {@code client.main} / {@code data.Main} / {@code obfuscate.DontObfuscate} /
 * {@code server.Main|MinecraftServer} / {@code util.profiling.jfr.event} 里。</p>
 *
 * <p>用“可读名字的<b>数量</b>”同样分不开：混淆产物 26 个可读简单名，而真实的 Yarn 映射
 * Fabric API 小模块只有 40 个左右——两个区间是重叠的。</p>
 *
 * <p>所以判据是<b>排除法</b>：把混淆器必然保留的那几个包挖掉之后，剩下的可读名不可能是混淆名，
 * 因此是决定性证据（见 {@link #isMojangEvidence(String)}）。这样既不依赖“某个版本恰好保留了
 * 哪些名字”，也不需要为不同具名方案（Yarn 的 {@code MinecraftClient} 与 Mojang 的
 * {@code Minecraft}）分别维护名单。</p>
 *
 * <p>认不出来的（例如未 remap 的 {@code -dev} 产物、或名字太少的极小 mod）一律返回
 * {@link NamespaceKind#UNKNOWN}，由上层要求显式指定 {@code --mappings} 或给出提示——猜错命名空间
 * 比承认不知道危险得多。</p>
 *
 * <p>这是纯本地检查：只读给定 JAR，不触网、不下载。扫描时忽略 {@code META-INF/} 下的条目
 * （签名块、{@code services}、语言文件等不会引入 Minecraft 类），并且只取每个 class
 * 常量池的开头一段，避免把整个 JAR 读进内存。</p>
 */
public final class ModNamespaceDetector {

    /** intermediary 类名的统一前缀；命中即可判定，不需要计数。 */
    static final String INTERMEDIARY_MARKER = "net/minecraft/class_";

    /** 所有 Minecraft 类名共有的前缀。 */
    static final String MINECRAFT_MARKER = "net/minecraft/";

    /**
     * 判定为 MOJANG 所需的“具名映射证据”条数下限。
     *
     * <p>见 {@link #isMojangEvidence(String)}：把混淆器必然保留的那几个包/类排除之后，
     * 实测混淆的 1.20.1 / 1.21.1 / 1.21.10 client.jar 证据数都是 <b>0</b>，而任何正常的具名映射
     * JAR 都远高于这个下限（实测 Fabric API 模块 ≥14、NeoForge universal ≥1000）。</p>
     */
    public static final int MOJANG_EVIDENCE_THRESHOLD = 8;

    /**
     * 混淆产物里被刻意保留下来的可读名所在的包。
     *
     * <p>混淆不是把 {@code net.minecraft} 下的名字全部打乱：启动器、数据生成器、游戏测试入口、
     * 以及 JFR 事件类必须保持稳定名字（否则外部工具没法用）。实测 1.21.1 client.jar 在
     * {@code net.minecraft} 下只有 26 个可读类名，<b>全部</b>落在下面这些包或类里。</p>
     *
     * <p>这些名字在正常映射里也存在，所以不能靠“命中某个地标类”来判断命名空间，只能把它们排除出
     * “具名映射”的证据之外。</p>
     */
    private static final String[] VANILLA_BOOTSTRAP_PREFIXES = {
            "net/minecraft/client/main/",
            "net/minecraft/client/data/",
            "net/minecraft/data/",
            "net/minecraft/gametest/",
            "net/minecraft/obfuscate/",
            "net/minecraft/util/profiling/jfr/event/",
    };

    /**
     * 少数几个保持可读的顶层类。
     *
     * <p>按<b>包含</b>匹配，因为它们的内部类（{@code Main$1}、{@code MinecraftServer$a}、
     * {@code MinecraftServer$c$1}……）同样保持可读。</p>
     */
    private static final Set<String> VANILLA_BOOTSTRAP_CLASSES = Set.of(
            "net/minecraft/server/Main",
            "net/minecraft/server/MinecraftServer",
            "net/minecraft/client/ClientBrandRetriever");

    /** 每个 class 参与扫描的常量池窗口大小；class 文件头 8 字节 + 常量池。 */
    private static final int CLASS_FILE_PREFIX = 64 * 1024;

    private ModNamespaceDetector() {
    }

    /**
     * 检测结果。
     *
     * @param namespace         判定出的命名空间
     * @param intermediaryHits  命中的不同 intermediary 类名个数
     * @param mojangEvidence    具名映射的证据条数（见 {@link #isMojangEvidence(String)}）
     * @param minecraftRefs     引用到的不同 {@code net/minecraft/*} 类名总数（含混淆名）
     * @param readableMojangHits 其中简单名可读的个数（仅用于展示）
     * @param scannedClasses    实际扫描的 class 条目数
     * @param modClasses        JAR 里的 class 条目总数（含被跳过的）
     * @param failure           读取失败原因；成功时为 {@code null}
     */
    public record Detection(NamespaceKind namespace,
                            int intermediaryHits,
                            int mojangEvidence,
                            int minecraftRefs,
                            int readableMojangHits,
                            int scannedClasses,
                            int modClasses,
                            @Nullable String failure) {

        public Detection {
            Objects.requireNonNull(namespace, "namespace");
        }

        /** 读取 JAR 是否失败。 */
        public boolean failed() {
            return failure != null;
        }

        /** 是否成功认出了命名空间（既没读失败，也不是 {@link NamespaceKind#UNKNOWN}）。 */
        public boolean detected() {
            return failure == null && namespace != NamespaceKind.UNKNOWN;
        }

        /** 是否完全没有 Minecraft 引用（说明这个 JAR 与 Minecraft 类名无关）。 */
        public boolean noMinecraftReferences() {
            return failure == null && minecraftRefs == 0 && intermediaryHits == 0;
        }

        /** 人类可读的一句话说明。 */
        public String describe() {
            if (failure != null) {
                return "无法读取（" + failure + "）";
            }
            return switch (namespace) {
                case INTERMEDIARY -> "intermediary（命中 " + intermediaryHits + " 个 net/minecraft/class_* 类名）";
                case MOJANG -> "mojang（具名映射证据 " + mojangEvidence + " 条，达到阈值 "
                        + MOJANG_EVIDENCE_THRESHOLD + "）";
                case UNKNOWN -> noMinecraftReferences()
                        ? "无 Minecraft 引用（扫描 " + scannedClasses + " 个类）"
                        : "未识别（intermediary 命中 " + intermediaryHits + "，具名映射证据 "
                                + mojangEvidence + " 条未达阈值 " + MOJANG_EVIDENCE_THRESHOLD
                                + "，共引用 " + minecraftRefs + " 个 net/minecraft/* 类）";
            };
        }
    }

    /** JAR 里 {@code net.minecraft.*} 引用的命名空间归属。 */
    public enum NamespaceKind {
        /** Fabric 系的 intermediary 名。 */
        INTERMEDIARY,
        /** Mojang 官方名（以及其它具名映射：只要类名可读，本工具就能把它们对齐到 {@code mojang}）。 */
        MOJANG,
        /** 认不出来：没有 {@code net.minecraft.*} 引用，或者信号太弱/自相矛盾。 */
        UNKNOWN
    }

    /**
     * 扫描一个 JAR 并判定命名空间。
     *
     * <p>本方法不抛异常：读取失败会体现在 {@link Detection#failure()} 上，由调用方决定是降级
     * 还是报错。</p>
     *
     * @param jar 待扫描的 JAR/ZIP
     */
    public static Detection detect(Path jar) {
        Objects.requireNonNull(jar, "jar");
        int scanned = 0;
        int modClasses = 0;
        Set<String> intermediary = new HashSet<>();
        MinecraftNames mojang = new MinecraftNames();
        byte[] buffer = new byte[CLASS_FILE_PREFIX];

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")) {
                    continue;
                }
                modClasses++;
                if (name.startsWith("META-INF/")) {
                    continue;
                }
                scanClass(zip.getInputStream(entry), buffer, intermediary, mojang);
                scanned++;
            }
        } catch (IOException e) {
            return new Detection(NamespaceKind.UNKNOWN, intermediary.size(), mojang.evidence(),
                    mojang.total(), mojang.readableSimpleNames(), scanned, modClasses,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        return new Detection(decide(intermediary.size(), mojang.evidence()), intermediary.size(),
                mojang.evidence(), mojang.total(), mojang.readableSimpleNames(), scanned, modClasses, null);
    }

    /**
     * 判定规则。
     *
     * <ol>
     *   <li>见到 {@code net/minecraft/class_} 就是 intermediary：官方名里不存在这个前缀。</li>
     *   <li>具名映射证据达到 {@link #MOJANG_EVIDENCE_THRESHOLD} 条才是 MOJANG。</li>
     *   <li>其余（包括“混淆产物那十来个保留名”）一律 UNKNOWN，交给上层决定。</li>
     * </ol>
     */
    static NamespaceKind decide(int intermediaryHits, int mojangEvidence) {
        if (intermediaryHits > 0) {
            return NamespaceKind.INTERMEDIARY;
        }
        if (mojangEvidence >= MOJANG_EVIDENCE_THRESHOLD) {
            return NamespaceKind.MOJANG;
        }
        return NamespaceKind.UNKNOWN;
    }

    private static void scanClass(InputStream in, byte[] buffer, Set<String> intermediary, MinecraftNames mojang)
            throws IOException {
        int total = readPrefix(in, buffer);
        if (total <= 0) {
            return;
        }
        collect(buffer, total, INTERMEDIARY_MARKER, intermediary::add);
        collect(buffer, total, MINECRAFT_MARKER, mojang::accept);
    }

    /** 读入 class 文件的开头一段；返回实际读到的字节数。 */
    private static int readPrefix(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        try (in) {
            while (total < buffer.length) {
                int read = in.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        }
        return total;
    }

    /**
     * 收集 {@code marker} 之后到下一个非法类名字符之间的“类名”，交给 {@code sink}。
     *
     * <p>同一个 class 常量池里通常有十几个条目引用同一个类（字段描述符、方法签名……），
     * 按不同类名去重后再计数，才能让“引用了多少个不同的 Minecraft 类”成为可比的量。</p>
     *
     * <p>注意 {@code from} 每次只前进到刚取到的类名<b>之后一个字节</b>：类名之间的分隔字节
     * （描述符里的 {@code L}/{@code ;}、{@code [} 等）本身不是类名字符，跳过它们会漏掉紧挨着的
     * 下一个类名——第一版实现就是这么丢掉 {@code net/minecraft/class_2561} 的。</p>
     */
    private static void collect(byte[] data, int length, String marker, java.util.function.Consumer<String> sink) {
        byte[] pattern = marker.getBytes(StandardCharsets.US_ASCII);
        int from = 0;
        while (from <= length - pattern.length) {
            int start = AsciiSearch.indexOf(data, from, length, pattern);
            if (start < 0) {
                return;
            }
            int relativeEnd = pattern.length;
            while (start + relativeEnd < length && isClassNameByte(data[start + relativeEnd])) {
                relativeEnd++;
            }
            sink.accept(new String(data, start, relativeEnd, StandardCharsets.US_ASCII));
            from = start + 1;
        }
    }

    /** 类名内部允许出现的字节：{@code A-Z a-z 0-9 _ $ /}。 */
    private static boolean isClassNameByte(byte b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                || b == '_' || b == '$' || b == '/';
    }

    /**
     * 类名的“简单名是否可读”。
     *
     * <p>混淆名是 {@code a}、{@code dwq}、{@code a$1} 这样的短小写名；官方名至少是
     * {@code client}、{@code Minecraft}、{@code level} 这样的词。判据：最后一段以大写字母开头，
     * 或者长度大于 3（把 {@code a}/{@code dwq} 这类排除掉，同时保留 {@code client} 这种小写包段）。</p>
     */
    static boolean isReadableSegment(String segment) {
        // 去掉内部类的 $ 后缀：net/minecraft/Foo$a 的简单名仍算 Foo
        int dollar = segment.indexOf('$');
        if (dollar > 0) {
            segment = segment.substring(0, dollar);
        }
        if (segment.isEmpty()) {
            return false;
        }
        if (Character.isUpperCase(segment.charAt(0))) {
            return true;
        }
        return segment.length() > 3;
    }

    /**
     * 一个 {@code net/minecraft/...} 类名是否是“具名映射”的证据。
     *
     * <p>判据是<b>排除法</b>而不是打分：只要这个名字既可读、又不在混淆产物保留的那几个包/类里，
     * 它就不可能是混淆名，因此是决定性证据。之所以不能反过来用“命中了某个已知映射类名”来判定，
     * 是因为每个映射版本都会改名（Yarn 叫 {@code client/MinecraftClient}、Mojang 叫
     * {@code client/Minecraft}），而“哪些名字混淆器会保留”是全局稳定的。</p>
     *
     * <p>只看简单名是不够的：{@code net.minecraft.client.main.Main} 的简单名也是可读的。
     * 实测混淆的 1.21.1 client.jar 有 26 个可读简单名、得分 64，
     * 而真实的 Yarn 映射 Fabric API 模块得分只有 46——两者根本不能用“可读数量”分开。</p>
     *
     * <p>{@code net/minecraft/class_NNNN} 不算证据：intermediary 由
     * {@link #INTERMEDIARY_MARKER} 单独判定。</p>
     */
    static boolean isMojangEvidence(String className) {
        if (className.startsWith(INTERMEDIARY_MARKER)) {
            return false;
        }
        if (!isReadableSegment(className.substring(className.lastIndexOf('/') + 1))) {
            return false;
        }
        for (String prefix : VANILLA_BOOTSTRAP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        for (String bootstrap : VANILLA_BOOTSTRAP_CLASSES) {
            if (className.equals(bootstrap) || className.startsWith(bootstrap + "$")) {
                return false;
            }
        }
        return true;
    }

    /** 统计 {@code net/minecraft/*} 的类名：总数、可读简单名个数、具名映射证据条数。 */
    private static final class MinecraftNames {
        private final Set<String> seen = new HashSet<>();
        private int readableSimpleNames;
        private int evidence;

        void accept(String className) {
            if (className.startsWith(INTERMEDIARY_MARKER)) {
                // class_310 之类的 intermediary 名单独统计，不计入具名映射的证据
                return;
            }
            if (!seen.add(className)) {
                return;
            }
            if (isReadableSegment(className.substring(className.lastIndexOf('/') + 1))) {
                readableSimpleNames++;
            }
            if (isMojangEvidence(className)) {
                evidence++;
            }
        }

        int readableSimpleNames() {
            return readableSimpleNames;
        }

        int evidence() {
            return evidence;
        }

        int total() {
            return seen.size();
        }
    }
}
