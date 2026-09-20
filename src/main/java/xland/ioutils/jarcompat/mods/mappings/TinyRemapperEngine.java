package xland.ioutils.jarcompat.mods.mappings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import xland.ioutils.jarcompat.mods.core.ModCompatException;

import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

/**
 * 基于 tiny-remapper 的 {@link JarRemapper} 实现。
 *
 * <p>配置上的几个选择：</p>
 * <ul>
 *   <li>{@code renameInvalidLocals(false)}：不重命名局部变量表。局部变量名对链接兼容性没有影响，
 *       关掉可以少写不少字节，也避免在调试信息不完整的 JAR 上做无谓的工作。</li>
 *   <li>不用 {@code --reverse}：映射方向由 Tiny 文件的列名决定，本项目只做
 *       {@code official -> target} 单向。</li>
 *   <li>喂完整 classpath：Minecraft 的类大量互相继承，继承来的成员要靠 classpath 解析，
 *       否则 tiny-remapper 只能按声明处推断，容易漏改。</li>
 * </ul>
 *
 * <p>这里<b>没有</b>启用 Mixin extension：本工具只 remap 上游的官方 JAR，从不 remap 用户的
 * mod JAR，而 Minecraft 官方 JAR 里没有 Mixin 注解。将来若支持 remap 库 JAR，再按需打开。</p>
 */
public final class TinyRemapperEngine implements JarRemapper {

    /** 默认留给 tiny-remapper 的线程数；0 表示由它自己决定。 */
    private final int threads;

    public TinyRemapperEngine() {
        this(0);
    }

    /**
     * @param threads tiny-remapper 的线程数；{@code 0} 表示自动
     */
    public TinyRemapperEngine(int threads) {
        this.threads = threads;
    }

    @Override
    public Path remap(Path source, Path target, MappingNamespace targetNs, Path mappings,
                      List<Path> classpath) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetNs, "targetNs");
        Objects.requireNonNull(mappings, "mappings");
        Objects.requireNonNull(classpath, "classpath");

        TinyRemapper.Builder builder = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(
                        mappings, MappingNamespace.OFFICIAL.label(), targetNs.label()))
                .renameInvalidLocals(false);
        if (threads > 0) {
            builder.threads(threads);
        }

        Path temp = target.resolveSibling(target.getFileName() + ".part");
        TinyRemapper remapper = builder.build();
        try {
            List<Path> path = new ArrayList<>(classpath);
            path.add(source);
            remapper.readClassPath(path.toArray(Path[]::new));
            remapper.readInputs(source);

            // tiny-remapper 只回调 class 条目，其余资源（manifest、pack.mcmeta、assets 索引……）
            // 要自己搬过去，所以先收集 class，再一次性写出完整 JAR。
            Map<String, byte[]> classes = new LinkedHashMap<>();
            remapper.apply((name, data) -> classes.put(name, data));
            writeJar(source, temp, classes);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new ModCompatException("重新映射 " + source.getFileName() + " 到 " + targetNs
                    + " 失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ModCompatException("重新映射 " + source.getFileName() + " 到 " + targetNs
                    + " 失败: " + cause, cause);
        } finally {
            remapper.finish();
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new ModCompatException("无法写入 " + target + ": " + e.getMessage(), e);
        }
        return target;
    }

    /**
     * 写出 remap 后的 JAR：先搬非 class 条目，再写 remap 出来的 class。
     *
     * <p>顺序上把 class 放后面没有特别含义，但<b>必须只用一个</b> {@link ZipOutputStream}：
     * 分两次打开同一个文件追加写入会丢掉其中一次的内容（第一版实现就踩了这个坑，
     * 产物里的 class 条目全丢了）。</p>
     */
    private static void writeJar(Path source, Path target, Map<String, byte[]> classes) throws IOException {
        try (var in = new ZipFile(source.toFile());
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().endsWith(".class")) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream stream = in.getInputStream(entry)) {
                    stream.transferTo(zip);
                }
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }
}
