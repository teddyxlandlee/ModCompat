package xland.ioutils.jarcompat.mods.env;

/**
 * 本工具处理的 Minecraft 命名空间。
 *
 * <p>只有三类名字：</p>
 * <ul>
 *   <li><b>官方名</b>：{@code 1.x} 上是混淆名（{@code dwq} 之类），{@code >= 26.x} 上因为不再混淆
 *       而与 {@link #MOJANG} 是<b>同一个</b>命名空间。它不是 remap 的目标，而是<b>起点</b>——
 *       官方 JAR 下载下来就在这个空间里，只有往 {@link #INTERMEDIARY} / {@link #MOJANG} 转换才有意义，
 *       因此本枚举不为它单独设一个取值。</li>
 *   <li>{@link #INTERMEDIARY}：Fabric 的稳定名（{@code net/minecraft/class_310}），也是 Fabric
 *       Loader 在运行期把原版 remap 过去的目标。</li>
 *   <li>{@link #MOJANG}：具名映射给出的可读名（{@code net/minecraft/client/Minecraft}），
 *       也是 NeoForge 1.20.2+ 的运行时命名空间。</li>
 * </ul>
 */
public enum MinecraftNamespace {

    /** Fabric 的 intermediary。 */
    INTERMEDIARY("intermediary"),

    /** Mojang 官方 mappings 的可读名（Yarn 等其它具名映射同样归入这一类）。 */
    MOJANG("mojang");

    private final String label;

    MinecraftNamespace(String label) {
        this.label = label;
    }

    /** 映射文件里使用的命名空间标识。 */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
