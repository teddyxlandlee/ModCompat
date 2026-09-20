package xland.ioutils.jarcompat.mods.mappings;

import java.nio.file.Path;
import java.util.List;

/**
 * 把一份 JAR 从 {@code official} 重新映射到目标命名空间。
 *
 * <p>接口只表达「进去一个官方 JAR、出来一个对齐后的 JAR」，引擎的选型与配置（tiny-remapper、
 * classpath、线程数……）都在实现里，判定层与编排层不依赖任何具体 remapper。</p>
 */
public interface JarRemapper {

    /**
     * 把 {@code source} 从官方命名空间重新映射到 {@code target}。
     *
     * @param source      待 remap 的官方 JAR
     * @param target      输出路径（由调用方决定，通常是缓存里带变体后缀的文件）
     * @param targetNs    目标命名空间
     * @param mappings    {@code official -> targetNs} 的 Tiny v2 映射文件
     * @param classpath   remap 时的 classpath（解析继承与引用用；不含 {@code source} 自身）
     * @return {@code target}
     * @throws xland.ioutils.jarcompat.mods.core.ModCompatException remap 失败
     */
    Path remap(Path source, Path target, MappingNamespace targetNs, Path mappings, List<Path> classpath);
}
