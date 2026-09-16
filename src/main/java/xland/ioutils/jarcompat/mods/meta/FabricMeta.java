package xland.ioutils.jarcompat.mods.meta;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.mods.core.LibraryParser;
import xland.ioutils.jarcompat.mods.core.MetaClient;
import xland.ioutils.jarcompat.mods.core.ModCompatException;
import xland.ioutils.jarcompat.mods.core.Resource;

/**
 * Fabric 元数据：某个 Minecraft 版本下最新的 Loader 版本，以及该版本的 profile 库列表。
 *
 * <p>对应 DEV_GUIDE §3.2：Loader 版本取 {@code loaders[0].loader.version}（未指定 override 时），
 * 库列表取 {@code /profile/json} 的 {@code libraries}，全部作为 libs 加入（Fabric 不区分 jar 和 libs）。</p>
 */
public final class FabricMeta {

    /** Fabric Loader 版本列表，追加 {@code <mcVersion>}。 */
    public static final String LOADER_META_BASE = "https://meta.fabricmc.net/v2/versions/loader/";

    private final MetaClient client;

    public FabricMeta(MetaClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 指定 Minecraft 版本下最新的 Fabric Loader 版本。
     *
     * @throws ModCompatException loaders 为空或缺少版本号
     */
    public String latestLoaderVersion(String mcVersion) {
        String url = LOADER_META_BASE + encode(mcVersion);
        JsonArray loaders = client.array(url);
        if (loaders.isEmpty()) {
            throw new ModCompatException("Fabric 没有为 Minecraft " + mcVersion + " 提供 Loader 版本（" + url + "）");
        }
        @Nullable JsonObject first = loaders.getObject(0);
        @Nullable JsonObject loader = first == null ? null : first.getObject("loader");
        @Nullable String version = loader == null ? null : loader.getString("version");
        if (version == null || version.isBlank()) {
            throw new ModCompatException("Fabric Loader 元数据格式异常（" + url + "）：缺少 loaders[0].loader.version");
        }
        return version;
    }

    /** 指定 Minecraft + Loader 版本的 profile 库列表。 */
    public List<Resource> libraries(String mcVersion, String loaderVersion) {
        String url = LOADER_META_BASE + encode(mcVersion) + "/" + encode(loaderVersion) + "/profile/json";
        JsonObject profile = client.object(url);
        return LibraryParser.parseLibraries(profile.getArray("libraries"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
