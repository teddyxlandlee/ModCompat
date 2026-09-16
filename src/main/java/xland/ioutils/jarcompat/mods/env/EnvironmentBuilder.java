package xland.ioutils.jarcompat.mods.env;

import java.util.ArrayList;
import java.util.List;

import com.grack.nanojson.JsonObject;

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
 */
public final class EnvironmentBuilder {

    private final MetaClient client;
    private final MojangMeta mojang;
    private final FabricMeta fabric;
    private final NeoForgeMeta neoForge;

    public EnvironmentBuilder(MetaClient client) {
        this.client = client;
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
     */
    public BuiltEnvironment build(String label, String mcVersion, boolean withFabric, boolean withNeoForge,
                                  String fabricOverride, String neoForgeOverride) {
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
            return new BuiltEnvironment(new EnvironmentVersions(mcVersion, fabricVersion, neoVersion), resources);
        } catch (ModCompatException e) {
            throw new ModCompatException("环境 " + label + "（Minecraft " + mcVersion + "）: " + e.getMessage(), e);
        }
    }
}
