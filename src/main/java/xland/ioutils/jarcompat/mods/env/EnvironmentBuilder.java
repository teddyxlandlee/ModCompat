package xland.ioutils.jarcompat.mods.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.grack.nanojson.JsonObject;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.mods.core.MetaClient;
import xland.ioutils.jarcompat.mods.core.ModCompatException;
import xland.ioutils.jarcompat.mods.core.Resource;
import xland.ioutils.jarcompat.mods.meta.FabricMeta;
import xland.ioutils.jarcompat.mods.meta.MojangMeta;
import xland.ioutils.jarcompat.mods.meta.NeoForgeMeta;

/**
 * 按 DEV_GUIDE §2/§3 构建一侧环境的上游库列表：
 *
 * <pre>
 * libs = [mcJar, ...mcLibs]
 * if fabric:   libs += fabricLibs          // Fabric 不区分 jar 和 libs，全部作为 libs
 * if neoforge: libs += [neoForgeJar, ...neoForgeLibs]
 * </pre>
 *
 * <p>未指定 override 时使用该 loader 在该侧 Minecraft 版本下的最新版本。</p>
 *
 * <p>构建完成的资源会按 {@link MappingsDecision} 打上命名空间变体（只有真正承载 Minecraft 代码的
 * 资源才有变体，见 {@link MappingsDecision#tag(List)}）。变体只改写 {@link Resource#variant()}，
 * 基础坐标（DEV_GUIDE §2 用来做同坐标过滤的 {@code coords}）保持不变。</p>
 */
public final class EnvironmentBuilder {

    private final MetaClient client;
    private final MojangMeta mojang;
    private final FabricMeta fabric;
    private final NeoForgeMeta neoForge;

    public EnvironmentBuilder(MetaClient client) {
        this.client = Objects.requireNonNull(client, "client");
        this.mojang = new MojangMeta(client);
        this.fabric = new FabricMeta(client);
        this.neoForge = new NeoForgeMeta(client);
    }

    /**
     * 构建一侧环境。
     *
     * @param label            侧标签（{@code A}/{@code B}），仅用于错误信息
     * @param mcVersion        Minecraft 版本号
     * @param withFabric       是否启用 Fabric
     * @param withNeoForge     是否启用 NeoForge
     * @param fabricOverride   指定的 Fabric Loader 版本，可为 {@code null}
     * @param neoForgeOverride 指定的 NeoForge 版本，可为 {@code null}
     * @param mappings         本次比较的命名空间决策
     */
    public BuiltEnvironment build(String label, String mcVersion, boolean withFabric, boolean withNeoForge,
                                  @Nullable String fabricOverride, @Nullable String neoForgeOverride,
                                  MappingsDecision mappings) {
        Objects.requireNonNull(mappings, "mappings");
        try {
            JsonObject versionMeta = mojang.versionMeta(mcVersion);
            List<Resource> resources = new ArrayList<>();
            resources.add(mojang.clientJar(mcVersion, versionMeta));
            resources.addAll(mojang.libraries(versionMeta));

            String fabricVersion = null;
            if (withFabric) {
                fabricVersion = fabricOverride != null && !fabricOverride.isBlank()
                        ? fabricOverride
                        : fabric.latestLoaderVersion(mcVersion);
                resources.addAll(fabric.libraries(mcVersion, fabricVersion));
            }

            String neoVersion = null;
            if (withNeoForge) {
                neoVersion = neoForgeOverride != null && !neoForgeOverride.isBlank()
                        ? neoForgeOverride
                        : neoForge.latestVersion(mcVersion);
                resources.add(neoForge.universalJar(neoVersion));
                byte[] installer = client.bytes(neoForge.installerUrl(neoVersion));
                resources.addAll(neoForge.libraries(installer));
            }
            // 官方映射只在真的要 remap 时才需要下载，所以这里只准备资源描述
            xland.ioutils.jarcompat.mods.core.Resource clientMappings =
                    mappings.remapNeeded() ? mojang.clientMappings(mcVersion, versionMeta) : null;
            return new BuiltEnvironment(
                    new EnvironmentVersions(mcVersion, fabricVersion, neoVersion, clientMappings),
                    mappings.tag(resources));
        } catch (ModCompatException e) {
            throw new ModCompatException("环境 " + label + "（Minecraft " + mcVersion + "）: " + e.getMessage(), e);
        }
    }
}
