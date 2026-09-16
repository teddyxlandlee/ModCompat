package xland.ioutils.jarcompat.mods.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

class LibraryParserTest {

    private static JsonArray libraries(String json) throws JsonParserException {
        return JsonParser.object().from(json).getArray("libraries");
    }

    @Test
    @DisplayName("优先使用 downloads.artifact.url")
    void usesArtifactUrl() throws Exception {
        List<Resource> resources = LibraryParser.parseLibraries(libraries("""
                {"libraries": [
                  {"name": "com.google.guava:guava:33.0.0-jre",
                   "downloads": {"artifact": {"url": "https://libraries.minecraft.net/guava.jar"}},
                   "url": "https://ignored.example/"}
                ]}
                """));
        assertEquals(1, resources.size());
        assertEquals("com.google.guava:guava:33.0.0-jre", resources.get(0).coords());
        assertEquals("https://libraries.minecraft.net/guava.jar", resources.get(0).url());
    }

    @Test
    @DisplayName("没有 artifact.url 时用 entry.url 按 Maven 规则拼接（Fabric profile 的形式）")
    void fallsBackToRepositoryUrl() throws Exception {
        List<Resource> resources = LibraryParser.parseLibraries(libraries("""
                {"libraries": [
                  {"name": "net.fabricmc:fabric-loader:0.19.5", "url": "https://maven.fabricmc.net/"},
                  {"name": "org.ow2.asm:asm:9.10.1", "url": "https://maven.fabricmc.net"}
                ]}
                """));
        assertEquals(2, resources.size());
        assertEquals("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
                resources.get(0).url());
        assertEquals("https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
                resources.get(1).url());
    }

    @Test
    @DisplayName("downloads.artifact 存在但 url 不是字符串时退回 entry.url")
    void fallsBackWhenArtifactUrlMissing() throws Exception {
        List<Resource> resources = LibraryParser.parseLibraries(libraries("""
                {"libraries": [
                  {"name": "org.lwjgl:lwjgl:3.3.3:natives-windows",
                   "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"}},
                   "url": "https://libraries.minecraft.net/"}
                ]}
                """));
        assertEquals("https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar",
                resources.get(0).url());
    }

    @Test
    @DisplayName("两者都没有时抛 ModCompatException")
    void failsWithoutAnyUrl() throws Exception {
        JsonArray libs = libraries("""
                {"libraries": [{"name": "a:b:1.0"}]}
                """);
        ModCompatException error = assertThrows(ModCompatException.class,
                () -> LibraryParser.parseLibraries(libs));
        assertTrue(error.getMessage().contains("a:b:1.0"), error.getMessage());
    }

    @Test
    @DisplayName("缺少 name 时抛 ModCompatException")
    void failsWithoutName() throws Exception {
        JsonArray libs = libraries("""
                {"libraries": [{"url": "https://example/"}]}
                """);
        assertThrows(ModCompatException.class, () -> LibraryParser.parseLibraries(libs));
    }

    @Test
    @DisplayName("null 视为空列表，保持顺序")
    void handlesNullAndOrder() throws Exception {
        assertEquals(List.of(), LibraryParser.parseLibraries(null));
        List<Resource> resources = LibraryParser.parseLibraries(libraries("""
                {"libraries": [
                  {"name": "a:a:1", "url": "https://example/"},
                  {"name": "b:b:2", "url": "https://example/"}
                ]}
                """));
        assertEquals(List.of("a:a:1", "b:b:2"), resources.stream().map(Resource::coords).toList());
    }
}
