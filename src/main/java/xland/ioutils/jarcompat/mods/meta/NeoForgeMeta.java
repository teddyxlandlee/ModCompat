package xland.ioutils.jarcompat.mods.meta;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import xland.ioutils.jarcompat.mods.core.LibraryParser;
import xland.ioutils.jarcompat.mods.core.MavenCoords;
import xland.ioutils.jarcompat.mods.core.MetaClient;
import xland.ioutils.jarcompat.mods.core.ModCompatException;
import xland.ioutils.jarcompat.mods.core.Resource;
import xland.ioutils.jarcompat.mods.core.Zips;

/**
 * NeoForge 元数据：最新版本选择、universal JAR、installer 内的库列表。
 *
 * <p>对应 DEV_GUIDE §3.3：版本列表来自 NeoForge Maven 的 versions API，universal JAR 与 installer
 * 都通过 Maven 规则拼接地址，installer 下载后作为 ZIP 读取 {@code /version.json} 取 {@code libraries}。</p>
 */
public final class NeoForgeMeta {

    /** 版本列表 API。 */
    public static final String VERSIONS_URL =
            "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";

    /** NeoForge Maven 仓库根地址。 */
    public static final String MAVEN_REPO = "https://maven.neoforged.net/releases";

    /** NeoForge 的 Maven groupId。 */
    public static final String GROUP = "net.neoforged";

    /** NeoForge 的 Maven artifactId。 */
    public static final String ARTIFACT = "neoforge";

    private final MetaClient client;

    public NeoForgeMeta(MetaClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 指定 Minecraft 版本下最新的 NeoForge 版本。
     *
     * @throws ModCompatException 元数据格式异常或没有候选版本
     */
    public String latestVersion(String mcVersion) {
        JsonObject data = client.object(VERSIONS_URL);
        JsonArray versions = data.getArray("versions");
        if (versions == null) {
            throw new ModCompatException("NeoForge 版本元数据格式异常（" + VERSIONS_URL + "）：缺少 versions 数组");
        }
        List<String> all = new ArrayList<>(versions.size());
        for (Object raw : versions) {
            if (raw instanceof String text) {
                all.add(text);
            }
        }
        List<String> candidates = NeoForgeVersions.candidates(all, mcVersion);
        if (candidates.isEmpty()) {
            throw new ModCompatException("NeoForge 没有为 Minecraft " + mcVersion
                    + "（版本前缀 " + NeoForgeVersions.prefixFor(mcVersion) + "）提供版本");
        }
        return NeoForgeVersions.pickLatest(candidates);
    }

    /** NeoForge universal JAR（坐标带 {@code universal} classifier）。 */
    public Resource universalJar(String neoVersion) {
        MavenCoords coords = new MavenCoords(GROUP, ARTIFACT, neoVersion, "universal", "jar");
        return new Resource(MavenCoords.artifactUrl(MAVEN_REPO, coords), coords.canonical());
    }

    /** NeoForge installer JAR 的下载地址（installer 只用于读取其中的 {@code version.json}）。 */
    public String installerUrl(String neoVersion) {
        MavenCoords coords = new MavenCoords(GROUP, ARTIFACT, neoVersion, "installer", "jar");
        return MavenCoords.artifactUrl(MAVEN_REPO, coords);
    }

    /**
     * 从 installer JAR 的 {@code /version.json} 中解析库列表。
     *
     * @param installerArchive installer JAR 的字节
     */
    public List<Resource> libraries(byte[] installerArchive) {
        byte[] versionJson = Zips.readEntry(installerArchive, "version.json");
        JsonObject json = MetaClient.parseObject(new String(versionJson, StandardCharsets.UTF_8),
                "NeoForge installer 的 version.json");
        return LibraryParser.parseLibraries(json.getArray("libraries"));
    }
}
