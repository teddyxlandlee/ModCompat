package xland.ioutils.jarcompat.mods.core;

import java.util.ArrayList;
import java.util.List;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.jspecify.annotations.Nullable;

/**
 * 把 Mojang / Fabric / NeoForge 元数据中的 {@code libraries} 数组解析成 {@link Resource} 列表。
 *
 * <p>规则（对应 DEV_GUIDE §3.1 的 {@code parseLibraries}）：</p>
 * <ol>
 *   <li>{@code coords = entry.name}；</li>
 *   <li>若 {@code entry.downloads.artifact.url} 是字符串，则直接作为下载地址；</li>
 *   <li>否则若 {@code entry.url} 是字符串，则按 Maven 仓库规则拼接下载地址；</li>
 *   <li>两者都没有时抛出 {@link ModCompatException}。</li>
 * </ol>
 *
 * <p>JSON 解析统一使用 {@code com.grack:nanojson}，本项目不自行实现 JSON 解析。</p>
 */
public final class LibraryParser {

    private LibraryParser() {
    }

    /** 解析 {@code libraries} 数组；{@code null} 视为空数组。 */
    public static List<Resource> parseLibraries(@Nullable JsonArray libraries) {
        List<Resource> result = new ArrayList<>();
        if (libraries == null) {
            return List.of();
        }
        for (int i = 0; i < libraries.size(); i++) {
            Object raw = libraries.get(i);
            if (!(raw instanceof JsonObject entry)) {
                throw new ModCompatException("libraries[" + i + "] 不是 JSON 对象: " + raw);
            }
            result.add(parseEntry(entry, i));
        }
        return List.copyOf(result);
    }

    private static Resource parseEntry(JsonObject entry, int index) {
        @Nullable String coords = entry.getString("name");
        if (coords == null || coords.isBlank()) {
            throw new ModCompatException("libraries[" + index + "] 缺少 name（Maven 坐标）");
        }
        coords = coords.trim();

        @Nullable String url = artifactUrl(entry);
        if (url == null) {
            @Nullable String repository = entry.getString("url");
            if (repository != null && !repository.isBlank()) {
                url = MavenCoords.artifactUrl(repository, MavenCoords.parse(coords));
            }
        }
        if (url == null) {
            throw new ModCompatException("库 " + coords
                    + " 既没有 downloads.artifact.url，也没有 url（Maven 仓库地址），无法确定下载地址");
        }
        return new Resource(url, coords);
    }

    /** 读取 {@code downloads.artifact.url}，不存在或类型不对时返回 {@code null}。 */
    private static @Nullable String artifactUrl(JsonObject entry) {
        JsonObject downloads = entry.getObject("downloads");
        if (downloads == null) {
            return null;
        }
        JsonObject artifact = downloads.getObject("artifact");
        if (artifact == null) {
            return null;
        }
        String url = artifact.getString("url");
        return url == null || url.isBlank() ? null : url;
    }
}
