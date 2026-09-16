package xland.ioutils.jarcompat.mods.core;

import java.util.Locale;
import java.util.Objects;

/**
 * Maven 坐标 {@code group:artifact:version[:classifier][@extension]} 的解析与仓库 URL 拼接。
 *
 * <p>对应 DEV_GUIDE 中的 {@code parseMavenCoords(coords)} 与
 * {@code getMavenArtifactUrl(repoBase, {group, artifact, version, classifier, extension})}，
 * 分别实现为 {@link #parse(String)} 与 {@link #artifactUrl(String, MavenCoords)}。</p>
 *
 * <p>支持的写法：</p>
 * <ul>
 *   <li>{@code group:artifact:version}</li>
 *   <li>{@code group:artifact:version:classifier}</li>
 *   <li>{@code group:artifact:version@extension}</li>
 *   <li>{@code group:artifact:version:classifier@extension}</li>
 * </ul>
 */
public record MavenCoords(String group, String artifact, String version, String classifier, String extension) {

    /** 未指定 {@code @extension} 时使用的默认扩展名。 */
    public static final String DEFAULT_EXTENSION = "jar";

    public MavenCoords {
        group = requirePart(group, "group");
        artifact = requirePart(artifact, "artifact");
        version = requirePart(version, "version");
        if (classifier != null) {
            classifier = classifier.trim();
            if (classifier.isEmpty()) {
                classifier = null;
            }
        }
        extension = extension == null || extension.isBlank()
                ? DEFAULT_EXTENSION
                : extension.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析 Maven 坐标字符串。
     *
     * @param coords {@code group:artifact:version[:classifier][@extension]}
     * @return 解析结果
     * @throws IllegalArgumentException 坐标格式非法
     */
    public static MavenCoords parse(String coords) {
        Objects.requireNonNull(coords, "coords");
        String text = coords.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Maven 坐标为空");
        }

        String extension = null;
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            extension = text.substring(at + 1).trim();
            text = text.substring(0, at);
            if (extension.isEmpty()) {
                throw new IllegalArgumentException("Maven 坐标缺少扩展名: " + coords);
            }
        }

        String[] parts = text.split(":", -1);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Maven 坐标至少需要 group:artifact:version 三段: " + coords);
        }
        if (parts.length > 4) {
            throw new IllegalArgumentException("Maven 坐标最多支持 group:artifact:version:classifier 四段: " + coords);
        }
        String classifier = parts.length == 4 ? parts[3] : null;
        return new MavenCoords(parts[0], parts[1], parts[2], classifier, extension);
    }

    /**
     * 按 Maven 仓库目录规则拼接构件下载地址：
     * {@code <repo>/<group 的 '.' 换成 '/' >/<artifact>/<version>/<artifact>-<version>[-<classifier>].<extension>}。
     *
     * @param repositoryBase 仓库根地址，例如 {@code https://libraries.minecraft.net/}
     * @param coords         构件坐标
     * @return 构件下载地址
     */
    public static String artifactUrl(String repositoryBase, MavenCoords coords) {
        Objects.requireNonNull(repositoryBase, "repositoryBase");
        Objects.requireNonNull(coords, "coords");
        String base = repositoryBase.trim();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("Maven 仓库地址为空（坐标 " + coords.canonical() + "）");
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        StringBuilder sb = new StringBuilder(base.length() + 96);
        sb.append(base)
                .append(coords.group().replace('.', '/')).append('/')
                .append(coords.artifact()).append('/')
                .append(coords.version()).append('/')
                .append(coords.artifact()).append('-').append(coords.version());
        if (coords.classifier() != null) {
            sb.append('-').append(coords.classifier());
        }
        return sb.append('.').append(coords.extension()).toString();
    }

    /**
     * 规范化坐标文本：{@code group:artifact:version[:classifier]}，
     * 仅当扩展名不是默认 {@code jar} 时追加 {@code @extension}。
     */
    public String canonical() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(group).append(':').append(artifact).append(':').append(version);
        if (classifier != null) {
            sb.append(':').append(classifier);
        }
        if (!DEFAULT_EXTENSION.equals(extension)) {
            sb.append('@').append(extension);
        }
        return sb.toString();
    }

    private static String requirePart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Maven 坐标的 " + name + " 为空");
        }
        return value.trim();
    }
}
