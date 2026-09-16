package xland.ioutils.jarcompat.mods.meta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.mods.core.ModCompatException;

/**
 * NeoForge 版本号规则：Minecraft 版本 → 版本前缀，以及版本列表的解析与排序。
 *
 * <p>前缀规则（DEV_GUIDE §3.3）：</p>
 * <ul>
 *   <li>第一段是 {@code "1"}：去掉 {@code "1"}，取接下来两段，不足补 {@code "0"}。
 *       例如 {@code 1.21 -> 21.0}、{@code 1.21.1 -> 21.1}；</li>
 *   <li>第一段不是 {@code "1"}：保留所有段，至少三段，不足补 {@code "0"}。
 *       例如 {@code 26.1 -> 26.1.0}、{@code 26.1.2 -> 26.1.2}。</li>
 * </ul>
 *
 * <p>版本排序解析：匹配 {@code ^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta))?$}，只比较 major/minor/patch
 * 数字部分，忽略 alpha/beta 后缀；无法识别的版本 {@link #parse(String)} 返回 {@code null}，
 * 排序时整组排在可识别版本之前，组内按“自然序”（数字段按数值比较）排序。
 * 这样 NeoForge 新式的四段版本号（例如 {@code 26.2.0.88}、{@code 26.3.0.1-beta}）也能被正确挑出最新的一个。</p>
 */
public final class NeoForgeVersions {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta))?$");
    private static final Pattern NUMERIC_RUN = Pattern.compile("\\d+");

    private NeoForgeVersions() {
    }

    /** 可识别的三段版本号（alpha/beta 后缀被忽略）。 */
    public record Version(long major, long minor, long patch) implements Comparable<Version> {

        @Override
        public int compareTo(Version other) {
            int c = Long.compare(major, other.major);
            if (c != 0) {
                return c;
            }
            c = Long.compare(minor, other.minor);
            if (c != 0) {
                return c;
            }
            return Long.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    /**
     * 计算 Minecraft 版本对应的 NeoForge 版本前缀。
     *
     * @param mcVersion Minecraft 版本号，例如 {@code 1.21.1}、{@code 26.1}
     * @throws IllegalArgumentException 版本号为 {@code null} 或空白（{@code null} 不是合法值，这里显式拒绝）
     */
    public static String prefixFor(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("Minecraft 版本号为空");
        }
        String[] parts = mcVersion.trim().split("\\.");
        if (parts.length > 0 && "1".equals(parts[0])) {
            String second = parts.length > 1 ? parts[1] : "0";
            String third = parts.length > 2 ? parts[2] : "0";
            return second + "." + third;
        }
        String first = parts.length > 0 && !parts[0].isBlank() ? parts[0] : "0";
        String second = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "0";
        String third = parts.length > 2 && !parts[2].isBlank() ? parts[2] : "0";
        return first + "." + second + "." + third;
    }

    /**
     * 按 {@code ^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta))?$} 解析版本号。
     *
     * @param version 版本号，可为 {@code null}
     * @return 解析结果；入参为 {@code null} 或无法识别时返回 {@code null}
     */
    public static @Nullable Version parse(@Nullable String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(version.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new Version(Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 过滤出属于某个 Minecraft 版本的候选 NeoForge 版本（{@code v.startsWith(prefix + ".")}）。
     *
     * @param versions  版本列表本身必须非空；列表中的 {@code null} 元素会被跳过
     * @param mcVersion Minecraft 版本号
     */
    public static List<String> candidates(List<@Nullable String> versions, String mcVersion) {
        Objects.requireNonNull(versions, "versions");
        String prefix = prefixFor(mcVersion) + ".";
        List<String> result = new ArrayList<>();
        for (String version : versions) {
            if (version != null && version.startsWith(prefix)) {
                result.add(version);
            }
        }
        return result;
    }

    /**
     * 从候选版本里挑出最新的一个。
     *
     * <p>可识别版本按数字部分升序排在后面，无法识别的版本排在前面（组内按自然序），因此优先返回
     * 一个格式规范的最新版本；若所有候选都无法识别，则返回自然序最大的那个。</p>
     *
     * @param versions 候选版本；{@code null} 或空列表都视为“没有候选”
     * @throws ModCompatException 候选为空
     */
    public static String pickLatest(@Nullable List<String> versions) {
        if (versions == null || versions.isEmpty()) {
            throw new ModCompatException("没有任何候选 NeoForge 版本可供选择");
        }
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String version : versions) {
            (parse(version) == null ? unknown : known).add(version);
        }
        unknown.sort(NeoForgeVersions::naturalCompare);
        known.sort(Comparator.comparing(NeoForgeVersions::parse));
        return known.isEmpty() ? unknown.getLast() : known.getLast();
    }

    /**
     * 自然序比较：把字符串切成数字段与非数字段，数字段按数值比较（例如 {@code 26.1.0.9 < 26.1.0.10}），
     * 其余按字符比较，结果稳定且可重复。
     */
    public static int naturalCompare(String a, String b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            boolean da = Character.isDigit(ca);
            boolean db = Character.isDigit(cb);
            if (da && db) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                int c = compareNumericRun(a.substring(startA, i), b.substring(startB, j));
                if (c != 0) {
                    return c;
                }
            } else if (da != db) {
                return da ? 1 : -1;
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static int compareNumericRun(String a, String b) {
        String na = stripLeadingZeros(a);
        String nb = stripLeadingZeros(b);
        if (na.length() != nb.length()) {
            return Integer.compare(na.length(), nb.length());
        }
        return na.compareTo(nb);
    }

    private static String stripLeadingZeros(String value) {
        int i = 0;
        while (i < value.length() - 1 && value.charAt(i) == '0') {
            i++;
        }
        String stripped = value.substring(i);
        // 纯数字段里不应出现非数字字符；NUMERIC_RUN 仅用于文档化该假设
        return NUMERIC_RUN.matcher(stripped).matches() ? stripped : value;
    }
}
