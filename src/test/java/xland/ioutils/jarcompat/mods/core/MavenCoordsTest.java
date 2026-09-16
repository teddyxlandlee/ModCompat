package xland.ioutils.jarcompat.mods.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MavenCoordsTest {

    @Test
    @DisplayName("解析 group:artifact:version")
    void parsesSimpleCoords() {
        MavenCoords coords = MavenCoords.parse("com.google.guava:guava:33.0.0-jre");
        assertEquals("com.google.guava", coords.group());
        assertEquals("guava", coords.artifact());
        assertEquals("33.0.0-jre", coords.version());
        assertNull(coords.classifier());
        assertEquals("jar", coords.extension());
        assertEquals("com.google.guava:guava:33.0.0-jre", coords.canonical());
    }

    @Test
    @DisplayName("解析带 classifier 的坐标")
    void parsesClassifier() {
        MavenCoords coords = MavenCoords.parse("net.neoforged:neoforge:21.1.250:universal");
        assertEquals("net.neoforged", coords.group());
        assertEquals("neoforge", coords.artifact());
        assertEquals("21.1.250", coords.version());
        assertEquals("universal", coords.classifier());
        assertEquals("jar", coords.extension());
        assertEquals("net.neoforged:neoforge:21.1.250:universal", coords.canonical());
    }

    @Test
    @DisplayName("解析 @extension 与 classifier@extension")
    void parsesExtension() {
        MavenCoords plain = MavenCoords.parse("org.lwjgl:lwjgl:3.3.3@zip");
        assertEquals("zip", plain.extension());
        assertNull(plain.classifier());
        assertEquals("org.lwjgl:lwjgl:3.3.3@zip", plain.canonical());

        MavenCoords full = MavenCoords.parse("org.lwjgl:lwjgl:3.3.3:natives-linux@zip");
        assertEquals("natives-linux", full.classifier());
        assertEquals("zip", full.extension());
        assertEquals("org.lwjgl:lwjgl:3.3.3:natives-linux@zip", full.canonical());
    }

    @Test
    @DisplayName("非法坐标报错")
    void rejectsInvalidCoords() {
        assertThrows(IllegalArgumentException.class, () -> MavenCoords.parse("a:b"));
        assertThrows(IllegalArgumentException.class, () -> MavenCoords.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MavenCoords.parse("a:b:c:d:e"));
        assertThrows(IllegalArgumentException.class, () -> MavenCoords.parse("a:b:c@"));
        assertThrows(IllegalArgumentException.class, () -> MavenCoords.parse("::"));
    }

    @Test
    @DisplayName("按 Maven 仓库规则拼接下载地址")
    void buildsArtifactUrl() {
        assertEquals(
                "https://libraries.minecraft.net/com/google/guava/guava/33.0.0-jre/guava-33.0.0-jre.jar",
                MavenCoords.artifactUrl("https://libraries.minecraft.net/",
                        MavenCoords.parse("com.google.guava:guava:33.0.0-jre")));

        // 仓库地址末尾缺少 / 时自动补齐
        assertEquals(
                "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
                MavenCoords.artifactUrl("https://maven.fabricmc.net",
                        MavenCoords.parse("net.fabricmc:fabric-loader:0.19.5")));

        // classifier
        assertEquals(
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-installer.jar",
                MavenCoords.artifactUrl("https://maven.neoforged.net/releases",
                        MavenCoords.parse("net.neoforged:neoforge:21.1.250:installer")));

        // extension
        assertEquals(
                "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.zip",
                MavenCoords.artifactUrl("https://libraries.minecraft.net/",
                        MavenCoords.parse("org.lwjgl:lwjgl:3.3.3:natives-linux@zip")));
    }

    @Test
    @DisplayName("空仓库地址报错")
    void rejectsEmptyRepository() {
        assertThrows(IllegalArgumentException.class, () -> MavenCoords.artifactUrl("  ",
                MavenCoords.parse("a:b:1.0")));
    }
}
