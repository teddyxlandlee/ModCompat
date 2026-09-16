package xland.ioutils.jarcompat.mods.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

/**
 * 元数据客户端：按 URL 取字节并用 nanojson 解析成 {@link JsonObject}/{@link JsonArray}。
 *
 * <p>同一个 URL 在一次运行中只请求一次（进程内缓存）：Mojang 版本清单、版本元数据、
 * Fabric profile、NeoForge 版本列表/安装包都会被两侧共用。</p>
 */
public final class MetaClient {

    private final Fetcher fetcher;
    private final Map<String, byte[]> bytes = new HashMap<>();
    private final Map<String, JsonObject> objects = new HashMap<>();
    private final Map<String, JsonArray> arrays = new HashMap<>();

    public MetaClient(Fetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /** 取回原始字节（带缓存）。 */
    public byte[] bytes(String url) {
        byte[] cached = bytes.get(url);
        if (cached != null) {
            return cached;
        }
        byte[] fetched;
        try {
            fetched = fetcher.get(url);
        } catch (IOException e) {
            throw new ModCompatException("无法获取 " + url + ": " + e.getMessage(), e);
        }
        if (fetched == null) {
            throw new ModCompatException("无法获取 " + url + ": 响应为空");
        }
        bytes.put(url, fetched);
        return fetched;
    }

    /** 把 {@code url} 的响应解析为 JSON 对象。 */
    public JsonObject object(String url) {
        return objects.computeIfAbsent(url, u -> {
            String text = new String(bytes(u), StandardCharsets.UTF_8);
            try {
                return JsonParser.object().from(text);
            } catch (JsonParserException e) {
                throw new ModCompatException("无法解析 JSON（" + u + "）: " + e.getMessage(), e);
            }
        });
    }

    /** 把 {@code url} 的响应解析为 JSON 数组。 */
    public JsonArray array(String url) {
        return arrays.computeIfAbsent(url, u -> {
            String text = new String(bytes(u), StandardCharsets.UTF_8);
            try {
                return JsonParser.array().from(text);
            } catch (JsonParserException e) {
                throw new ModCompatException("无法解析 JSON 数组（" + u + "）: " + e.getMessage(), e);
            }
        });
    }

    /** 把一段 JSON 文本解析为 JSON 对象。 */
    public static JsonObject parseObject(String text, String description) {
        try {
            return JsonParser.object().from(text);
        } catch (JsonParserException e) {
            throw new ModCompatException("无法解析 JSON（" + description + "）: " + e.getMessage(), e);
        }
    }
}
