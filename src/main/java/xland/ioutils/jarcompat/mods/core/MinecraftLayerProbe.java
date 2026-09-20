package xland.ioutils.jarcompat.mods.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipFile;

/**
 * 检查一份 JAR 是否真的提供了 Minecraft 类。
 *
 * <p>用途是给命名空间决策兜底：如果一侧的 classpath 上有 {@code net/minecraft/**} 的类、另一侧
 * 一个都没有，那么无论报告怎么说，Minecraft 层的结论在缺失的那一侧都是<b>盲的</b>——mod 对
 * Minecraft 的引用在那一侧根本解析不到，会被记成“外部引用”后跳过，于是“没发现问题”变成了
 * 假阴性。这个检查与命名空间无关，只看包名，因此对混淆 JAR（{@code net/minecraft/dwq.class}）、
 * intermediary（{@code net/minecraft/class_310.class}）与未混淆 JAR 一律有效。</p>
 *
 * <p>它只是<b>兜底断言</b>，不是主判据：主判据是 {@code MappingsDecision} 的命名空间推导。</p>
 */
public final class MinecraftLayerProbe {

    private MinecraftLayerProbe() {
    }

    /**
     * 两侧探测结果。
     *
     * @param classesA   环境 A 的 classpath 提供的 {@code net/minecraft/**} 类数量
     * @param classesB   环境 B 的 classpath 提供的 {@code net/minecraft/**} 类数量
     * @param warnings   不对称时给出的提示
     */
    public record Result(int classesA, int classesB, List<String> warnings) {

        public Result {
            warnings = List.copyOf(warnings);
        }

        /** 两侧是否都真的提供了 Minecraft 类（或两侧都没有）。 */
        public boolean conclusive() {
            return (classesA > 0) == (classesB > 0);
        }
    }

    /**
     * 探测两侧 classpath 是否都提供了 Minecraft 类。
     *
     * @param libA 环境 A 的本地 JAR 列表
     * @param libB 环境 B 的本地 JAR 列表
     */
    public static Result probe(List<Path> libA, List<Path> libB) {
        Objects.requireNonNull(libA, "libA");
        Objects.requireNonNull(libB, "libB");
        int classesA = countClasses(libA);
        int classesB = countClasses(libB);

        List<String> warnings = new ArrayList<>(2);
        if (classesA > 0 && classesB == 0) {
            warnings.add(blindnessWarning("A", "B", classesB));
        } else if (classesB > 0 && classesA == 0) {
            warnings.add(blindnessWarning("B", "A", classesA));
        }
        return new Result(classesA, classesB, warnings);
    }

    private static String blindnessWarning(String present, String missing, int missingCount) {
        return "环境 " + present + " 提供了 Minecraft 类，环境 " + missing + " 的 classpath 上却一个都没有"
                + "（" + missingCount + " 个）：mod 对 Minecraft 的引用在 " + missing
                + " 侧解析不到，会被记为“外部引用”后跳过，Minecraft 层的结论在该侧是盲的";
    }

    /**
     * 统计列表中各 JAR 里 {@code net/minecraft/} 下的 class 条目总数。
     *
     * @param jars classpath 上的 JAR；读不动的文件按 0 计
     */
    public static int countClasses(List<Path> jars) {
        Objects.requireNonNull(jars, "jars");
        int total = 0;
        for (Path jar : jars) {
            total += countClasses(jar);
        }
        return total;
    }

    /**
     * 统计一份 JAR 里 {@code net/minecraft/} 下的 class 条目数。
     *
     * @param jar JAR 路径；不存在或读不动时返回 0
     */
    public static int countClasses(Path jar) {
        Objects.requireNonNull(jar, "jar");
        int count = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("net/minecraft/") && name.endsWith(".class")) {
                    count++;
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return count;
    }
}
