package xland.ioutils.jarcompat.mods.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * JAR 下载缓存：把 {@link Resource} 落到本地文件，供 {@code JarCompat.check(...)} 以 {@code Path} 读取。
 *
 * <p>目录布局为 {@code <cacheDir>/<url 的 SHA-1>/<原始文件名>}：按 URL 哈希分目录既避免不同仓库的
 * 同名构件互相覆盖，又让 JarCompat 报告里显示的文件名保持可读（例如 {@code lwjgl-3.3.3.jar}）。
 * 已存在且可读的缓存文件会被直接复用，因此重复运行不需要重新下载。</p>
 *
 * <p>带命名空间变体的资源（{@link Resource#variant()}）在文件名上多一个 {@code .<变体>} 后缀，
 * 例如 {@code client.jar.mapped-mojang}：同一份构件的不同命名空间版本互不覆盖。</p>
 */
public final class JarCache {

    private final Path root;
    private final Fetcher fetcher;
    private final @Nullable Consumer<String> progress;
    private int downloaded;
    private int reused;

    /**
     * @param root     缓存根目录
     * @param fetcher  下载器
     * @param progress 进度回调（每个真正发生的下载调用一次），可为 {@code null}
     */
    public JarCache(Path root, Fetcher fetcher, @Nullable Consumer<String> progress) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath();
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.progress = progress;
    }

    /** 缓存根目录。 */
    public Path root() {
        return root;
    }

    /** 本次运行真正发生的下载次数。 */
    public int downloadedCount() {
        return downloaded;
    }

    /** 本次运行直接复用缓存文件的次数。 */
    public int reusedCount() {
        return reused;
    }

    /**
     * 返回资源的本地路径：缓存命中则复用，否则下载并写入缓存。
     */
    public Path fetch(Resource resource) throws IOException {
        return fetch(resource, null);
    }

    /**
     * 返回资源在指定命名空间变体下的本地路径。
     *
     * <p>变体参与缓存键：同一份 {@code client.jar} 被 remap 成 mojang 与 intermediary 是两份不同的
     * 产物，缓存目录（按 URL 的 SHA-1 分目录）里也必须是两个文件，文件名上带 {@code .<变体>} 后缀，
     * 这样报告里仍然一眼能看出它来自哪个构件。真正的 remapping 落在这一步与下载之间
     * （见 {@code MappingsDecision}）；本版本尚未实现 remapper，因此变体只影响路径。</p>
     *
     * @param resource 资源（其 {@link Resource#variant()} 被忽略，变体由参数显式给出）
     * @param variant  命名空间变体，可为 {@code null}
     */
    public Path fetch(Resource resource, @Nullable String variant) throws IOException {
        Objects.requireNonNull(resource, "resource");
        Path target = localPath(resource, variant);
        if (isUsable(target)) {
            reused++;
            return target;
        }
        Files.createDirectories(target.getParent());
        if (progress != null) {
            progress.accept("下载 " + target.getFileName() + "  <- " + resource.displayName());
        }
        byte[] content = fetcher.get(resource.url());
        if (content.length == 0) {  // implicit null check
            throw new IOException("下载内容为空: " + resource.url());
        }
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(temp, content);
        if (!isUsable(temp)) {
            Files.deleteIfExists(temp);
            throw new IOException("下载内容不是有效的 JAR/ZIP: " + resource.url());
        }
        move(temp, target);
        downloaded++;
        return target;
    }

    /** 资源在缓存中的目标路径（不触发下载）。 */
    public Path localPath(Resource resource) {
        return localPath(resource, null);
    }

    /** 资源在缓存中、指定变体下的目标路径（不触发下载）。 */
    public Path localPath(Resource resource, @Nullable String variant) {
        Objects.requireNonNull(resource, "resource");
        Path directory = root.resolve(sha1Hex(resource.url()));
        String name = fileName(resource.url());
        if (variant == null || variant.isBlank()) {
            return directory.resolve(name);
        }
        int dot = name.lastIndexOf('.');
        String suffixed = dot > 0
                ? name.substring(0, dot) + "." + variant + name.substring(dot)
                : name + "." + variant;
        return directory.resolve(suffixed);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isUsable(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            if (Files.size(file) == 0) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar") || name.endsWith(".zip")) {
            return Zips.isReadableZip(file);
        }
        return true;
    }

    /** 由 URL 推导缓存文件名，保留原始名称以便报告可读。 */
    static String fileName(String url) {
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        String sanitized = sanitizePath(path);
        if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
            sanitized = "resource";
        }
        if (sanitized.length() > 160) {
            sanitized = sanitized.substring(sanitized.length() - 160);
        }
        return sanitized;
    }

    private static String sanitizePath(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            sb.append(keep ? c : '_');
        }
        return sb.toString();
    }

    static String sha1Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new InternalError("当前 JVM 不支持 SHA-1", e);
        }
    }
}
