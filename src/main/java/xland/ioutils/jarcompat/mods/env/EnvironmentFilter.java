package xland.ioutils.jarcompat.mods.env;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import xland.ioutils.jarcompat.mods.core.Resource;

/**
 * 两侧上游库的去重/过滤（DEV_GUIDE §2 的最后一步）。
 *
 * <p>规则：某一侧的资源只要与另一侧存在 Maven 坐标相同的资源（{@code isEquivalent}），就从该侧移除；
 * 同一侧内部坐标重复的资源只保留第一个。</p>
 *
 * <p>两侧都以“对方未过滤前的完整列表”为基准，因此结果是严格对称的：某个坐标相同于两侧的资源会同时
 * 从 lib-a 和 lib-b 中消失，剩下的就是两侧各自的差异部分。（若按 DEV_GUIDE 伪代码的字面顺序在循环里
 * 就地修改，先处理的一侧会把共有资源删掉，导致后处理的一侧反而保留它们，结果依赖处理顺序。）</p>
 */
public final class EnvironmentFilter {

    private EnvironmentFilter() {
    }

    /**
     * 过滤结果。
     *
     * @param resources                  过滤后保留的资源（保持原顺序）
     * @param removedSharedWithOtherSide 因与另一侧坐标相同而移除的数量
     * @param removedDuplicates          因本侧坐标重复而移除的数量
     */
    public record Result(List<Resource> resources, int removedSharedWithOtherSide, int removedDuplicates) {

        public Result {
            resources = List.copyOf(resources);
        }

        /** 过滤前的资源总数。 */
        public int rawCount() {
            return resources.size() + removedSharedWithOtherSide + removedDuplicates;
        }
    }

    /**
     * 过滤一侧的资源。
     *
     * @param ownResources   本侧未过滤的资源列表
     * @param otherSideCoords 另一侧未过滤资源的全部 Maven 坐标
     */
    public static Result filter(List<Resource> ownResources, Set<String> otherSideCoords) {
        Objects.requireNonNull(ownResources, "ownResources");
        Objects.requireNonNull(otherSideCoords, "otherSideCoords");
        Set<String> seen = new HashSet<>();
        List<Resource> kept = new ArrayList<>(ownResources.size());
        int shared = 0;
        int duplicates = 0;
        for (Resource resource : ownResources) {
            if (otherSideCoords.contains(resource.coords())) {
                shared++;
                continue;
            }
            if (!seen.add(resource.coords())) {
                duplicates++;
                continue;
            }
            kept.add(resource);
        }
        return new Result(kept, shared, duplicates);
    }

    /** 提取资源列表的全部 Maven 坐标（保持顺序、去重）。 */
    public static Set<String> coordsOf(List<Resource> resources) {
        Objects.requireNonNull(resources, "resources");
        Set<String> coords = new LinkedHashSet<>();
        for (Resource resource : resources) {
            coords.add(resource.coords());
        }
        return coords;
    }
}
