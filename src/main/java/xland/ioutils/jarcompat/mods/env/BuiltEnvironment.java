package xland.ioutils.jarcompat.mods.env;

import java.util.List;
import java.util.Objects;

import xland.ioutils.jarcompat.mods.core.Resource;

/**
 * 一侧环境构建完成后的结果：版本组合 + 该侧全部上游资源（尚未与其他侧做同坐标过滤）。
 *
 * <p>资源顺序即 classpath 顺序，按 DEV_GUIDE §2：
 * {@code mcJar, ...mcLibs, (fabricLibs), (neoForgeJar, ...neoForgeLibs)}。</p>
 */
public record BuiltEnvironment(EnvironmentVersions versions, List<Resource> resources) {

    public BuiltEnvironment {
        Objects.requireNonNull(versions, "versions");
        resources = List.copyOf(resources);
    }
}
