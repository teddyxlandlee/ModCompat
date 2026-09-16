package xland.ioutils.jarcompat.mods.env;

import java.util.Objects;

/**
 * 一侧环境的版本组合：Minecraft 版本 + 实际启用的 loader 版本（未启用的为 {@code null}）。
 */
public record EnvironmentVersions(String minecraftVersion, String fabricLoaderVersion, String neoForgeVersion) {

    public EnvironmentVersions {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
    }

    /**
     * 判断两个环境的 {@code (mcVersion, fabricLoaderVersion, neoForgeVersion)} 是否完全相同。
     *
     * <p>DEV_GUIDE §1：未启用的 loader 版本不参与“完全相同”判断；若完全相同则比较无意义。</p>
     *
     * @param fabricEnabled  本次是否启用 {@code --fabric}
     * @param neoForgeEnabled 本次是否启用 {@code --neoforge}
     */
    public boolean sameEnvironmentAs(EnvironmentVersions other, boolean fabricEnabled, boolean neoForgeEnabled) {
        if (other == null || !minecraftVersion.equals(other.minecraftVersion)) {
            return false;
        }
        if (fabricEnabled && !Objects.equals(fabricLoaderVersion, other.fabricLoaderVersion)) {
            return false;
        }
        if (neoForgeEnabled && !Objects.equals(neoForgeVersion, other.neoForgeVersion)) {
            return false;
        }
        return true;
    }

    /** 人类可读的版本描述，例如 {@code Minecraft 1.21.1 + Fabric Loader 0.19.5}。 */
    public String describe(boolean fabricEnabled, boolean neoForgeEnabled) {
        StringBuilder sb = new StringBuilder("Minecraft ").append(minecraftVersion);
        if (fabricEnabled) {
            sb.append(" + Fabric Loader ").append(fabricLoaderVersion);
        }
        if (neoForgeEnabled) {
            sb.append(" + NeoForge ").append(neoForgeVersion);
        }
        return sb.toString();
    }
}
