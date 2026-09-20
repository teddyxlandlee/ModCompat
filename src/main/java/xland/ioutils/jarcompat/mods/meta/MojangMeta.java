package xland.ioutils.jarcompat.mods.meta;

import java.util.List;
import java.util.Objects;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import xland.ioutils.jarcompat.mods.core.LibraryParser;
import xland.ioutils.jarcompat.mods.core.MetaClient;
import xland.ioutils.jarcompat.mods.core.ModCompatException;
import xland.ioutils.jarcompat.mods.core.Resource;

/**
 * Mojang 版本元数据：版本清单 → 版本 JSON → 原版 client JAR 与库列表。
 *
 * <p>对应 DEV_GUIDE §3.1：</p>
 * <ol>
 *   <li>拉取 {@code version_manifest_v2.json}；</li>
 *   <li>找到 {@code id == mcVersion} 的条目；</li>
 *   <li>拉取该条目的 {@code url} 指向的版本元数据；</li>
 *   <li>{@code mcJar.url = versionMeta.downloads.client.url}，
 *       {@code mcJar.coords = "com.mojang:minecraft:" + mcVersion}；</li>
 *   <li>{@code mcLibs = parseLibraries(versionMeta.libraries)}。</li>
 * </ol>
 */
public final class MojangMeta {

    /** 官方版本清单。 */
    public static final String VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final MetaClient client;

    public MojangMeta(MetaClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 解析 Minecraft 版本的元数据 JSON。
     *
     * @param mcVersion 版本号，例如 {@code 1.21.1}
     * @throws ModCompatException 清单里没有该版本，或版本元数据缺少 client 下载信息
     */
    public JsonObject versionMeta(String mcVersion) {
        JsonObject manifest = client.object(VERSION_MANIFEST_URL);
        JsonArray versions = manifest.getArray("versions");
        if (versions == null) {
            throw new ModCompatException("版本清单 " + VERSION_MANIFEST_URL + " 缺少 versions 数组");
        }
        for (Object raw : versions) {
            if (!(raw instanceof JsonObject entry)) {
                continue;
            }
            if (!mcVersion.equals(entry.getString("id"))) {
                continue;
            }
            String url = entry.getString("url");
            if (url == null || url.isBlank()) {
                throw new ModCompatException("Minecraft " + mcVersion + " 的清单条目缺少 url");
            }
            return client.object(url);
        }
        throw new ModCompatException("在 Mojang 版本清单中找不到 Minecraft 版本 " + mcVersion
                + "（可用 --version-a/--version-b 指定正确的版本号）");
    }

    /** 原版 client JAR，坐标为 {@code com.mojang:minecraft:<mcVersion>}。 */
    public Resource clientJar(String mcVersion, JsonObject versionMeta) {
        JsonObject downloads = versionMeta.getObject("downloads");
        JsonObject client = downloads == null ? null : downloads.getObject("client");
        String url = client == null ? null : client.getString("url");
        if (url == null || url.isBlank()) {
            throw new ModCompatException("Minecraft " + mcVersion + " 的版本元数据缺少 downloads.client.url");
        }
        return new Resource(url, "com.mojang:minecraft:" + mcVersion);
    }

    /**
     * 官方 mappings（ProGuard 文本），坐标为
     * {@code com.mojang:minecraft:<mcVersion>:client_mappings}。
     *
     * <p>这是 remap 官方 JAR 的<b>唯一</b>数据源：{@code 26.1} 之前官方 JAR 是混淆产物，而这份文件
     * 给的是 {@code mojang -> official}（左边可读名、右边混淆名），反向用即可。</p>
     *
     * @throws ModCompatException 该版本的元数据里没有 {@code downloads.client_mappings}
     */
    public Resource clientMappings(String mcVersion, JsonObject versionMeta) {
        JsonObject downloads = versionMeta.getObject("downloads");
        JsonObject mappings = downloads == null ? null : downloads.getObject("client_mappings");
        String url = mappings == null ? null : mappings.getString("url");
        if (url == null || url.isBlank()) {
            throw new ModCompatException("Minecraft " + mcVersion
                    + " 的版本元数据缺少 downloads.client_mappings.url（官方映射），无法反混淆");
        }
        return new Resource(url, "com.mojang:minecraft:" + mcVersion + ":client_mappings");
    }

    /** 版本元数据里的库列表。 */
    public List<Resource> libraries(JsonObject versionMeta) {
        return LibraryParser.parseLibraries(versionMeta.getArray("libraries"));
    }
}
