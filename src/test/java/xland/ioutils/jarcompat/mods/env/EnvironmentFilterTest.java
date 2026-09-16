package xland.ioutils.jarcompat.mods.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import xland.ioutils.jarcompat.mods.core.Resource;

class EnvironmentFilterTest {

    private static Resource resource(String coords) {
        return new Resource("https://example.invalid/" + coords.replace(':', '/') + ".jar", coords);
    }

    @Test
    @DisplayName("同坐标资源从两侧同时移除（对称过滤）")
    void removesSharedCoordsFromBothSides() {
        List<Resource> a = List.of(resource("com.mojang:minecraft:1.21.1"), resource("shared:lib:1.0"),
                resource("a:only:1.0"));
        List<Resource> b = List.of(resource("com.mojang:minecraft:1.21.4"), resource("shared:lib:1.0"),
                resource("b:only:1.0"));

        EnvironmentFilter.Result filteredA = EnvironmentFilter.filter(a, EnvironmentFilter.coordsOf(b));
        EnvironmentFilter.Result filteredB = EnvironmentFilter.filter(b, EnvironmentFilter.coordsOf(a));

        assertEquals(List.of("com.mojang:minecraft:1.21.1", "a:only:1.0"),
                filteredA.resources().stream().map(Resource::coords).toList());
        assertEquals(List.of("com.mojang:minecraft:1.21.4", "b:only:1.0"),
                filteredB.resources().stream().map(Resource::coords).toList());
        assertEquals(1, filteredA.removedSharedWithOtherSide());
        assertEquals(1, filteredB.removedSharedWithOtherSide());
        assertEquals(3, filteredA.rawCount());
    }

    @Test
    @DisplayName("同一侧重复坐标只保留第一个")
    void removesDuplicatesWithinSide() {
        List<Resource> a = List.of(resource("dup:lib:1.0"), resource("dup:lib:1.0"), resource("a:only:1.0"));
        EnvironmentFilter.Result filtered = EnvironmentFilter.filter(a, EnvironmentFilter.coordsOf(List.of()));
        assertEquals(List.of("dup:lib:1.0", "a:only:1.0"),
                filtered.resources().stream().map(Resource::coords).toList());
        assertEquals(1, filtered.removedDuplicates());
        assertEquals(0, filtered.removedSharedWithOtherSide());
        assertEquals(3, filtered.rawCount());
    }

    @Test
    @DisplayName("保持原有顺序")
    void keepsOrder() {
        List<Resource> a = List.of(resource("z:z:1"), resource("a:a:1"), resource("m:m:1"));
        EnvironmentFilter.Result filtered = EnvironmentFilter.filter(a, EnvironmentFilter.coordsOf(List.of()));
        assertEquals(List.of("z:z:1", "a:a:1", "m:m:1"),
                filtered.resources().stream().map(Resource::coords).toList());
    }

    @Test
    @DisplayName("coordsOf 去重")
    void coordsAreDistinct() {
        assertEquals(2, EnvironmentFilter.coordsOf(List.of(resource("x:x:1"), resource("x:x:1"),
                resource("y:y:1"))).size());
    }

    @Test
    @DisplayName("两侧环境是否完全相同：未启用的 loader 不参与判断")
    void versionsComparison() {
        EnvironmentVersions a = new EnvironmentVersions("1.21.1", null, null);
        EnvironmentVersions b = new EnvironmentVersions("1.21.1", "0.19.5", "21.1.250");

        assertTrue(a.sameEnvironmentAs(b, false, false));
        assertFalse(a.sameEnvironmentAs(b, true, false));
        assertFalse(a.sameEnvironmentAs(b, false, true));
        assertFalse(a.sameEnvironmentAs(new EnvironmentVersions("1.21.4", null, null), false, false));
        assertTrue(new EnvironmentVersions("1.21.1", "0.19.5", "21.1.250")
                .sameEnvironmentAs(b, true, true));
    }
}
