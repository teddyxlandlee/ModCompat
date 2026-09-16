package xland.ioutils.jarcompat.mods.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import xland.ioutils.jarcompat.mods.core.ModCompatException;

class NeoForgeVersionsTest {

    @Test
    @DisplayName("Minecraft 版本 -> NeoForge 版本前缀")
    void computesPrefix() {
        assertEquals("21.0", NeoForgeVersions.prefixFor("1.21"));
        assertEquals("21.1", NeoForgeVersions.prefixFor("1.21.1"));
        assertEquals("21.4", NeoForgeVersions.prefixFor("1.21.4"));
        assertEquals("20.1", NeoForgeVersions.prefixFor("1.20.1"));
        assertEquals("0.0", NeoForgeVersions.prefixFor("1"));
        assertEquals("26.1.0", NeoForgeVersions.prefixFor("26.1"));
        assertEquals("26.1.2", NeoForgeVersions.prefixFor("26.1.2"));
        assertEquals("26.3.0", NeoForgeVersions.prefixFor("26.3"));
    }

    @Test
    @DisplayName("按 ^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta))?$ 解析，忽略 alpha/beta")
    void parsesVersions() {
        assertEquals(new NeoForgeVersions.Version(21, 1, 250), NeoForgeVersions.parse("21.1.250"));
        assertEquals(new NeoForgeVersions.Version(21, 0, 3), NeoForgeVersions.parse("21.0.3-beta"));
        assertEquals(new NeoForgeVersions.Version(26, 1, 0), NeoForgeVersions.parse("26.1.0-alpha"));
        NeoForgeVersions.Version release = Objects.requireNonNull(NeoForgeVersions.parse("21.1.250"));
        NeoForgeVersions.Version beta = Objects.requireNonNull(NeoForgeVersions.parse("21.1.250-beta"));
        assertEquals(0, release.compareTo(beta));
    }

    @Test
    @DisplayName("无法识别的版本返回 null")
    void returnsNullForUnknown() {
        assertNull(NeoForgeVersions.parse("26.1.0.1-beta"));
        assertNull(NeoForgeVersions.parse("26.2.0.88"));
        assertNull(NeoForgeVersions.parse("0.25w14craftmine.3-beta"));
        assertNull(NeoForgeVersions.parse("26.1.0.0-alpha.1+snapshot-1"));
        assertNull(NeoForgeVersions.parse("abc"));
        assertNull(NeoForgeVersions.parse(null));
    }

    @Test
    @DisplayName("自然序：数字段按数值比较")
    void naturalOrder() {
        assertTrue(NeoForgeVersions.naturalCompare("26.1.0.9-beta", "26.1.0.10-beta") < 0);
        assertTrue(NeoForgeVersions.naturalCompare("26.1.0.10-beta", "26.1.0.9-beta") > 0);
        assertTrue(NeoForgeVersions.naturalCompare("21.9.0", "21.10.0") < 0);
        assertEquals(0, NeoForgeVersions.naturalCompare("21.1.250", "21.1.250"));
    }

    @Test
    @DisplayName("候选版本按前缀过滤")
    void filtersCandidates() {
        List<String> all = List.of("21.0.167", "21.1.250", "21.1.0", "21.10.3", "20.4.1");
        assertEquals(List.of("21.1.250", "21.1.0"), NeoForgeVersions.candidates(all, "1.21.1"));
        assertEquals(List.of("21.0.167"), NeoForgeVersions.candidates(all, "1.21"));
        assertEquals(List.of("21.10.3"), NeoForgeVersions.candidates(all, "1.21.10"));
    }

    @Test
    @DisplayName("三段版本号按数字挑最新（忽略 alpha/beta）")
    void picksLatestThreeSegment() {
        List<String> versions = new ArrayList<>(List.of("21.1.0-beta", "21.1.250", "21.1.9", "21.1.100"));
        assertEquals("21.1.250", NeoForgeVersions.pickLatest(versions));
    }

    @Test
    @DisplayName("四段版本号（无法识别）按自然序挑最新")
    void picksLatestFourSegment() {
        List<String> versions = new ArrayList<>(List.of(
                "26.1.0.0-alpha.1+snapshot-1",
                "26.1.0.9-beta",
                "26.1.0.10-beta",
                "26.1.0.2-beta"));
        assertEquals("26.1.0.10-beta", NeoForgeVersions.pickLatest(versions));

        assertEquals("26.2.0.88", NeoForgeVersions.pickLatest(new ArrayList<>(List.of(
                "26.2.0.9", "26.2.0.88", "26.2.0.87"))));
    }

    @Test
    @DisplayName("可识别版本优先于无法识别的版本")
    void prefersRecognizedVersions() {
        List<String> versions = new ArrayList<>(List.of("21.1.0.5-alpha", "21.1.9"));
        assertEquals("21.1.9", NeoForgeVersions.pickLatest(versions));
    }

    @Test
    @DisplayName("候选为空时报错")
    void failsOnEmptyCandidates() {
        assertThrows(ModCompatException.class, () -> NeoForgeVersions.pickLatest(List.of()));
        assertThrows(ModCompatException.class, () -> NeoForgeVersions.pickLatest(null));
    }
}
