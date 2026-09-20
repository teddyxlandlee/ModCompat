package xland.ioutils.jarcompat.mods.env;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import xland.ioutils.jarcompat.mods.cli.CliOptions;
import xland.ioutils.jarcompat.mods.cli.MappingsMode;
import xland.ioutils.jarcompat.mods.core.MinecraftVersion;
import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector;

/**
 * 一次比较里与命名空间相关的全部输入。
 *
 * <p>把它单独记录下来，是为了让判定层（{@link MappingsDecision#evaluate}）成为一个只依赖显式输入的
 * 纯函数：版本号、loader 开关、以及 mod JAR 自己的命名空间检测结果都从外面传进来，判定过程本身
 * 不读文件、不触网，因此可以完整地单测。</p>
 *
 * @param mode            用户给出的 {@code --mappings} 取值
 * @param mcVersionA      环境 A 的 Minecraft 版本
 * @param mcVersionB      环境 B 的 Minecraft 版本
 * @param fabric          是否启用 Fabric Loader
 * @param neoForge        是否启用 NeoForge
 * @param modNamespace    mod JAR 自身的命名空间检测结果
 */
public record MappingsRequest(MappingsMode mode,
                             String mcVersionA,
                             String mcVersionB,
                             boolean fabric,
                             boolean neoForge,
                             ModNamespaceDetector.Detection modNamespace) {

    public MappingsRequest {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(mcVersionA, "mcVersionA");
        Objects.requireNonNull(mcVersionB, "mcVersionB");
        Objects.requireNonNull(modNamespace, "modNamespace");
    }

    /** 从命令行选项与已完成的 mod JAR 检测结果构造。 */
    public static MappingsRequest of(CliOptions options, String mcVersionA, String mcVersionB,
                                     ModNamespaceDetector.Detection detection) {
        Objects.requireNonNull(options, "options");
        return new MappingsRequest(options.mappings(), mcVersionA, mcVersionB,
                options.fabric(), options.neoForge(), detection);
    }

    /** 检测 mod JAR 并构造请求。 */
    public static MappingsRequest detect(CliOptions options, String mcVersionA, String mcVersionB, Path program) {
        return of(options, mcVersionA, mcVersionB, ModNamespaceDetector.detect(program));
    }

    /** 两侧 Minecraft 版本（依次为 A、B）。 */
    public List<String> mcVersions() {
        return List.of(mcVersionA, mcVersionB);
    }

    /** 两侧版本是否跨越了“混淆 / 不混淆”的分界线。 */
    public boolean crossesGeneration() {
        return MinecraftVersion.isObfuscated(mcVersionA) != MinecraftVersion.isObfuscated(mcVersionB);
    }
}
