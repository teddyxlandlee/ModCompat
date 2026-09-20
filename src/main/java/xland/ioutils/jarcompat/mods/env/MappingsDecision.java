package xland.ioutils.jarcompat.mods.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.mods.cli.MappingsMode;
import xland.ioutils.jarcompat.mods.core.MinecraftVersion;
import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector;
import xland.ioutils.jarcompat.mods.core.ModNamespaceDetector.NamespaceKind;
import xland.ioutils.jarcompat.mods.core.Resource;

/**
 * 命名空间决策：把“{@code --mappings} 取值 + 两侧 Minecraft 版本 + loader 开关 + mod 自身的命名空间”
 * 归结为一组确定的结论——目标命名空间是什么、需不需要 remap、以及能不能给出 Minecraft 层的结论。
 *
 * <p>核心不变式：<b>命名空间是一次比较的全局属性</b>。判定结果里只有<b>一个</b>目标命名空间，
 * 两侧所有带 Minecraft 的资源都被对齐到它；绝不为两侧各自推导一个命名空间（那样比较出来的
 * “不兼容”只是命名空间不同，毫无意义）。</p>
 *
 * <p>另一个不变式：<b>mod JAR 本身从不 remap</b>。被对齐的是上游环境（官方 client.jar 等），
 * 也就是把库挪到 mod 所在的命名空间，而不是反过来改写用户的产物。因此目标命名空间要么等于
 * mod 的命名空间，要么就是 {@code null}（不处理）。</p>
 *
 * @param requestedMode     用户给出的 {@code --mappings} 取值
 * @param modNamespace      mod JAR 自身的命名空间检测结果
 * @param targetNamespace   两侧资源要被对齐到的命名空间（只有 {@code mojang} 与 {@code intermediary}
 *                          两种可能——官方名是 remap 的起点而不是目标）；{@code null} 表示不做任何对齐
 * @param remapNeeded       是否需要对上游资源做实际 remap（{@code true} 时本版本尚未实现，见 {@link #degraded()}）
 * @param mcLayerConclusive 本次比较能否对 Minecraft 层下结论
 * @param fabric            本次是否启用了 Fabric（仅用于 {@link #loaderHeuristic()} 的说明）
 * @param warnings          需要展示给用户的提示
 * @param notes             决策依据的说明（写入 JSON 报告）
 */
public record MappingsDecision(MappingsMode requestedMode,
                               ModNamespaceDetector.Detection modNamespace,
                               @Nullable MinecraftNamespace targetNamespace,
                               boolean remapNeeded,
                               boolean mcLayerConclusive,
                               boolean fabric,
                               List<String> warnings,
                               List<String> notes) {

    /** 上游资源被 remap 成 Mojang 官方名后的变体名。 */
    public static final String MOJANG_VARIANT = "mapped-mojang";

    /** 上游资源被 remap 成 Fabric intermediary 后的变体名。 */
    public static final String INTERMEDIARY_VARIANT = "mapped-intermediary";

    /** NeoForge universal JAR 的 Maven classifier；它的字节码用的是 Mojang 官方名。 */
    public static final String NEOFORGE_UNIVERSAL_CLASSIFIER = "universal";

    /** 官方 client.jar 的坐标前缀。 */
    public static final String MINECRAFT_COORDS_PREFIX = "com.mojang:minecraft:";

    /** NeoForge universal JAR 的坐标前缀。 */
    public static final String NEOFORGE_COORDS_PREFIX = "net.neoforged:neoforge:";

    public MappingsDecision {
        Objects.requireNonNull(requestedMode, "requestedMode");
        Objects.requireNonNull(modNamespace, "modNamespace");
        warnings = List.copyOf(warnings);
        notes = List.copyOf(notes);
    }

    /**
     * 本版本是否降级运行。
     *
     * <p>{@code true} 表示“按决策本该 remap，但实际没有做”（本版本尚未实现 remapping 引擎），
     * 或者“本该对齐却做不到”，此时 Minecraft 层的结论不可信，必须在报告里说清楚。</p>
     */
    public boolean degraded() {
        return remapNeeded || !mcLayerConclusive;
    }

    /** 目标命名空间对应的资源变体名；不需要对齐时为 {@code null}。 */
    public @Nullable String variantName() {
        if (targetNamespace == null || !remapNeeded) {
            return null;
        }
        return variantOf(targetNamespace);
    }

    /**
     * 目标命名空间是否只是从 loader 推断出来的（mod 与已启用的 loader 并不自洽）。
     *
     * <p>只会在 {@code auto} 的“mod 是 intermediary、却只启用了 {@code --neoforge}”这一种情形下为
     * {@code true}：NeoForge 的运行时是 mojang，而 mod 是 intermediary，目标只能从 loader 推断。</p>
     *
     * <p>{@code --fabric} + intermediary mod 是<b>正常</b>组合（Fabric 运行时就是 intermediary），
     * 因此不触发这个提醒。</p>
     */
    public boolean loaderHeuristic() {
        if (requestedMode != MappingsMode.AUTO || modNamespace.namespace() == NamespaceKind.UNKNOWN) {
            return false;
        }
        NamespaceKind expected = fabric ? NamespaceKind.INTERMEDIARY : NamespaceKind.MOJANG;
        return modNamespace.namespace() != expected;
    }

    /** 一行人类可读的决策摘要，用于报告头部。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(requestedMode.label()).append(" -> ");
        sb.append(targetNamespace == null ? "none" : targetNamespace.label());
        sb.append("（mod: ").append(modNamespace.namespace().name().toLowerCase(Locale.ROOT));
        if (remapNeeded) {
            sb.append("，需 remap ").append(variantName());
        } else if (targetNamespace != null) {
            sb.append("，无需 remap");
        }
        sb.append(mcLayerConclusive ? "；结论含 Minecraft 层" : "；仅库层结论");
        sb.append('）');
        return sb.toString();
    }

    /**
     * 把一侧的上游资源打上变体标记。
     *
     * <p>只有真正承载 Minecraft 代码、且会被 remap 的资源才有变体：官方 {@code client.jar} 与
     * NeoForge 的 {@code :universal} JAR。纯库（gson、netty……）与 Fabric Loader 的库字节码里
     * 没有 {@code net.minecraft.*}，不需要变体。</p>
     *
     * <p>{@link Resource#coords()}（基础身份）保持不变，因此两侧的“同一个构件”仍然按坐标过滤；
     * 变体只影响缓存键与报告。</p>
     */
    public List<Resource> tag(List<Resource> resources) {
        Objects.requireNonNull(resources, "resources");
        String variant = variantName();
        if (variant == null) {
            return List.copyOf(resources);
        }
        List<Resource> tagged = new ArrayList<>(resources.size());
        for (Resource resource : resources) {
            tagged.add(isMinecraftBearing(resource) ? resource.withVariant(variant) : resource);
        }
        return List.copyOf(tagged);
    }

    /** 该资源是否承载 Minecraft 代码（因此 remap 时会换一套类名）。 */
    public static boolean isMinecraftBearing(Resource resource) {
        String coords = resource.coords();
        if (coords.startsWith(MINECRAFT_COORDS_PREFIX)) {
            return true;
        }
        return coords.startsWith(NEOFORGE_COORDS_PREFIX) && coords.endsWith(":" + NEOFORGE_UNIVERSAL_CLASSIFIER);
    }

    // ------------------------------------------------------------------ 判定

    /** mod JAR 里没有任何 Minecraft 引用的决策：两侧都不需要动。 */
    private static MappingsDecision noMinecraftReferences(MappingsRequest request) {
        return new MappingsDecision(request.mode(), request.modNamespace(), null, false, true, request.fabric(), List.of(),
                List.of("mod JAR 读得动，但里面没有任何 net.minecraft.* 引用"
                        + "（既没有 intermediary 类名，也没有官方可读类名），无需对齐"));
    }

    /**
     * 按决策表求值。
     *
     * <p>规则见 {@code CliParser.usage()} 里的表格；要点是：</p>
     * <ul>
     *   <li>两侧都在 {@code >= 26.x}：不混淆，什么都不用做；显式请求 {@code intermediary} 是参数错误。</li>
     *   <li>版本跨越 {@code 26.x} 分界线：只有 mojang 是两侧共有的命名空间。</li>
     *   <li>都还在 {@code 1.x}：按 loader 推导目标命名空间，并要求与 mod 自身的命名空间自洽。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 显式指定的命名空间在该版本组合下不可能存在
     */
    public static MappingsDecision evaluate(MappingsRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.mode() == MappingsMode.NONE) {
            return new MappingsDecision(request.mode(), request.modNamespace(), null, false, false, request.fabric(),
                    List.of("--mappings none：显式关闭映射处理，两侧原样使用官方（可能混淆的）JAR；"
                            + "结论只覆盖库层与 JAR 中稳定的具名类"),
                    List.of("用户显式要求不处理映射"));
        }
        if (request.modNamespace().failed()) {
            // 读不动 mod JAR 就不要假装它没有 Minecraft 引用
            return decideAutoUnreadable(request);
        }
        // 版本与用户请求的可行性先判：这与 mod 里有没有 Minecraft 引用无关，
        // 否则“两侧都是 1.x 却要 intermediary”这种请求会因为 mod 恰好没引用 MC 类而漏过。
        if (request.mode() == MappingsMode.INTERMEDIARY
                && MinecraftVersion.isObfuscated(request.mcVersionA())
                && MinecraftVersion.isObfuscated(request.mcVersionB())) {
            throw new IllegalArgumentException("--mappings intermediary 在 Minecraft " + request.mcVersionA()
                    + " / " + request.mcVersionB() + " 上不可用：两侧都是 1.x，intermediary 自 26.1 起才存在");
        }
        if (request.mode() == MappingsMode.INTERMEDIARY && request.crossesGeneration()) {
            // 跨代 + 显式 intermediary：>= 26.x 侧根本没有 intermediary 文件可下载，
            // 只把 1.x 侧对齐过去只会让两侧更不一致。降级为只比库层，而不是报错。
            return unfixable(request, "--mappings intermediary 不能用于跨代比较：Minecraft "
                    + unobfuscatedSide(request)
                    + " 没有 intermediary 文件可下载（intermediary 只覆盖 1.x 的历史版本），"
                    + "只对齐 1.x 侧反而会让两侧不一致");
        }
        if (request.modNamespace().noMinecraftReferences()) {
            return noMinecraftReferences(request);
        }
        if (!request.modNamespace().detected()) {
            // 走到了这里说明确实扫到了 Minecraft 类名，但既没有 net/minecraft/class_* 前缀，
            // 可读类名也少得可怜（例如 1.x 上直接针对混淆名编译的产物，或名字极少的极小 mod）。
            // 建立不了映射链，只能拒绝给出 Minecraft 层结论。
            return decideAutoUnknown(request);
        }
        return switch (request.mode()) {
            case AUTO -> decideAuto(request);
            case MOJANG -> decideMojang(request);
            case INTERMEDIARY -> decideIntermediary(request);
            case NONE -> throw new IllegalStateException("已在前面处理");
        };
    }

    /** 跨代比较里“不混淆”的那一侧版本；用于错误信息。 */
    private static String unobfuscatedSide(MappingsRequest request) {
        return MinecraftVersion.isObfuscated(request.mcVersionA()) ? request.mcVersionB() : request.mcVersionA();
    }

    /** {@code --mappings mojang}：对齐到官方映射名；在 {@code >= 26.x} 上退化为恒等操作。 */
    private static MappingsDecision decideMojang(MappingsRequest request) {
        if (MinecraftVersion.isUnobfuscated(request.mcVersionA())
                && MinecraftVersion.isUnobfuscated(request.mcVersionB())) {
            return new MappingsDecision(request.mode(), request.modNamespace(), null, false, true, request.fabric(),
                    List.of(),
                    List.of("两侧都是 >= " + MinecraftVersion.FIRST_UNOBFUSCATED_MAJOR
                            + ".x：官方 JAR 本来就未混淆，mojang 是恒等操作，不启动 remapper"));
        }
        MinecraftNamespace target = MinecraftNamespace.MOJANG;
        NamespaceKind kind = request.modNamespace().namespace();
        if (kind == NamespaceKind.INTERMEDIARY) {
            return remap(request, target, List.of(), List.of("mod 是 intermediary，对齐到 mojang 需要 official -> intermediary 的映射（由 NeoForge 侧的 deobfuscated 官方 JAR 提供）"));
        }
        if (kind == NamespaceKind.MOJANG) {
            return aligned(request, target, List.of(), List.of("mod 已经是具名映射，上游无需 remap"));
        }
        return unsupportedLoader(request, target, kind);
    }

    /** {@code --mappings intermediary}：对齐到 Fabric 的 intermediary。 */
    private static MappingsDecision decideIntermediary(MappingsRequest request) {
        // 走到这里时两侧都是 >= 26.x（1.x 与跨代的情况已在 evaluate 里处理掉）
        MinecraftNamespace target = MinecraftNamespace.INTERMEDIARY;
        NamespaceKind kind = request.modNamespace().namespace();
        if (kind == NamespaceKind.INTERMEDIARY) {
            return aligned(request, target, List.of(), List.of("mod 已经是 intermediary，上游无需 remap"));
        }
        if (kind == NamespaceKind.MOJANG) {
            return remap(request, target, List.of(),
                    List.of("mod 是具名映射，而 >= " + MinecraftVersion.FIRST_UNOBFUSCATED_MAJOR
                            + ".x 的官方 JAR 本来就是未混淆的官方名：对齐到 intermediary 需要 official -> intermediary 的映射"));
        }
        return unsupportedLoader(request, target, kind);
    }

    /** {@code --mappings auto}：按版本代际与 loader 推导。 */
    private static MappingsDecision decideAuto(MappingsRequest request) {
        if (!request.crossesGeneration() && MinecraftVersion.isUnobfuscated(request.mcVersionA())) {
            return new MappingsDecision(request.mode(), request.modNamespace(), null, false, true, request.fabric(),
                    List.of(),
                    List.of("两侧都是 >= " + MinecraftVersion.FIRST_UNOBFUSCATED_MAJOR
                            + ".x：原版 / Fabric / NeoForge 都不需要映射"));
        }
        NamespaceKind kind = request.modNamespace().namespace();
        return switch (kind) {
            case MOJANG -> new MappingsDecision(request.mode(), request.modNamespace(),
                    MinecraftNamespace.MOJANG, false, true, request.fabric(), List.of(),
                    List.of("mod 使用具名映射：上游（原版 / NeoForge）本来就在这个命名空间，无需 remap"
                            + (request.crossesGeneration() ? "；跨代比较因此可以照常进行" : "")));
            case INTERMEDIARY -> decideAutoIntermediary(request);
            case UNKNOWN -> decideAutoUnknown(request);
        };
    }

    /**
     * {@code auto}，但 mod 的命名空间不可识别。
     *
     * <p>可能是名字太少（极小 mod）、也可能是在 {@code 1.x} 上直接针对混淆名编译的产物。
     * 无论哪种，都建立不了从 mod 命名空间到上游命名空间的映射链，因此不宣称结论完整。</p>
     */
    private static MappingsDecision decideAutoUnknown(MappingsRequest request) {
        List<String> warnings = new ArrayList<>(1);
        warnings.add("mod JAR 里没有任何可识别的 Minecraft 命名空间（既没有 intermediary 的 "
                + "net/minecraft/class_* 前缀，可读类名也只有 "
                + request.modNamespace().readableMojangHits() + " 个）：无法建立映射链。"
                + "如果这是未 remap 的工具链产物（Yarn 的 -dev 输出、或直接引用混淆名），"
                + "这些引用在两侧都解析不到，Minecraft 层会静默漏报");
        return new MappingsDecision(request.mode(), request.modNamespace(), null, false, false, request.fabric(), warnings,
                List.of("mod 的命名空间不可识别，未做任何对齐"));
    }

    /** {@code auto}，但 mod JAR 根本读不出类名。 */
    private static MappingsDecision decideAutoUnreadable(MappingsRequest request) {
        return new MappingsDecision(request.mode(), request.modNamespace(), null, false, false, request.fabric(),
                List.of("无法读取 mod JAR 的类名（" + request.modNamespace().failure()
                        + "）：如果该 mod 确实引用 Minecraft 类，Minecraft 层的结论不可信"),
                List.of("mod JAR 的命名空间检测失败，未做任何对齐"));
    }

    /** {@code auto} + mod 是 intermediary：只有在 loader 唯一确定了命名空间时才敢动手。 */
    private static MappingsDecision decideAutoIntermediary(MappingsRequest request) {
        if (request.crossesGeneration()) {
            // 跨代时没有选择余地：>= 26.x 侧只有 mojang，1.x 侧的官方 JAR 也只有 mojang 可用，
            // 但 mod 是 intermediary。这个组合无论启用哪个 loader 都建立不了映射链。
            return unsupportedLoader(request, MinecraftNamespace.MOJANG, NamespaceKind.INTERMEDIARY);
        }
        if (!request.fabric() && !request.neoForge()) {
            return new MappingsDecision(request.mode(), request.modNamespace(), null, false, false, request.fabric(),
                    List.of("mod 使用 Fabric 的 intermediary 命名空间，但既没有 --fabric 也没有 --neoforge："
                            + "无法确定要把原版 JAR 对齐到哪个命名空间（intermediary 还是 mojang），"
                            + "本次只比库层。若 mod 本来就在 Fabric 上运行，请加 --fabric；"
                            + "两侧都是 >= 26.x 时则本来就不需要映射"),
                    List.of("mod 是 intermediary，但没有可用的 loader 提示"));
        }
        if (request.fabric() && request.neoForge()) {
            return new MappingsDecision(request.mode(), request.modNamespace(), null, false, false, request.fabric(),
                    List.of("--fabric 与 --neoforge 同时启用：NeoForge 的运行时是 mojang 命名空间，"
                            + "而 mod 是 intermediary，两者不能共用同一个目标命名空间，本次只比库层。"
                            + "请分别比较，或用 --mappings 显式指定要采用哪一套"),
                    List.of("两侧 loader 同时启用，命名空间冲突"));
        }
        // 只有一个 loader：它就是权威
        boolean withFabric = request.fabric();
        MinecraftNamespace target = withFabric ? MinecraftNamespace.INTERMEDIARY : MinecraftNamespace.MOJANG;
        String why = withFabric
                ? "mod 是 intermediary，唯一启用的 loader 是 --fabric（运行时就是 intermediary）："
                        + "原版 JAR 需要反混淆后重新映射到 intermediary，与 mod 自洽"
                : "mod 是 intermediary，但唯一启用的 loader 是 --neoforge（运行时为 mojang）："
                        + "原版 JAR 需要反混淆到 mojang 才能与 mod 的引用对上，目标命名空间是按 loader 推断的";
        // --fabric + intermediary 是正常组合，不需要额外提醒；
        // --neoforge + intermediary 的目标命名空间是推断出来的，由 loaderHeuristic() 提醒。
        return remap(request, target, List.of(), List.of(why));
    }

    /** 需要实际 remap。 */
    private static MappingsDecision remap(MappingsRequest request, MinecraftNamespace target,
                                          List<String> warnings, List<String> notes) {
        List<String> allWarnings = new ArrayList<>(warnings);
        allWarnings.add("本版本尚未实现 remapping 引擎：两侧上游资源将保持官方（"
                + (MinecraftVersion.isObfuscated(request.mcVersionA())
                || MinecraftVersion.isObfuscated(request.mcVersionB()) ? "1.x 上为混淆名" : "未混淆名")
                + "）原名，Minecraft 层的结论不可信，只应参考库层");
        List<String> allNotes = new ArrayList<>(notes);
        allNotes.add("目标命名空间 " + target.label() + "（变体 " + variantOf(target) + "）；remap 需要映射链 "
                + request.modNamespace().namespace().name().toLowerCase(Locale.ROOT) + " <- official（本版本未执行）");
        return new MappingsDecision(request.mode(), request.modNamespace(), target, true, false, request.fabric(),
                allWarnings, allNotes);
    }

    /** 已经对齐，不需要 remap。 */
    private static MappingsDecision aligned(MappingsRequest request, MinecraftNamespace target,
                                            List<String> warnings, List<String> notes) {
        return new MappingsDecision(request.mode(), request.modNamespace(), target, false, true, request.fabric(), warnings, notes);
    }

    /**
     * mod 引用了 Minecraft 类，但没有可用的 loader 能确定目标命名空间。
     *
     * <p>这就是“{@code modNamespace != targetNamespace} 且不存在合法映射链”的情形：不报错，
     * 但明确拒绝给出 Minecraft 层的结论。</p>
     *
     * @param target 本来想对齐到的命名空间；{@code null} 表示连候选都没有
     */
    private static MappingsDecision unsupportedLoader(MappingsRequest request,
                                                      @Nullable MinecraftNamespace target, NamespaceKind kind) {
        boolean hasLoader = request.fabric() || request.neoForge();
        String reason;
        String action;
        if (request.crossesGeneration()) {
            reason = "跨代比较时两侧只有 mojang 一种命名空间，而 mod 的命名空间是 "
                    + kind.name().toLowerCase(Locale.ROOT) + "：没有合法的映射链能把 1.x 侧的官方 JAR "
                    + "对齐到 mod 的命名空间";
            action = "跨代比较请使用以 mojang 官方名编译的 mod（NeoForge 侧通常是），"
                    + "否则只能参考库层结论";
        } else if (!hasLoader) {
            reason = "mod 确实引用了 Minecraft 类、命名空间是 " + kind.name().toLowerCase(Locale.ROOT)
                    + "，但没有启用任何 loader，无从确定目标命名空间";
            action = "请加 --fabric（对齐到 intermediary）或 --neoforge（对齐到 mojang），"
                    + "或用 --mappings 显式指定；也可以用 --mappings none 接受只比库层";
        } else {
            reason = "mod 确实引用了 Minecraft 类，但既不是 intermediary 也不是已知的官方可读名"
                    + "（可能是 Yarn 名，或是 1.x 上直接针对混淆名编译的产物）";
            action = "请用 remapJar 之类的产物替换 mod JAR，或显式指定 --mappings；"
                    + "两侧都是 >= 26.x 时本来就不需要映射";
        }
        return new MappingsDecision(request.mode(), request.modNamespace(), null, false, false, request.fabric(),
                List.of(reason + "；本次只比库层。" + action),
                List.of("无法建立从 mod 命名空间到上游命名空间的映射链"));
    }

    /** 该版本组合下目标命名空间在物理上不存在（例如 1.x 上的 intermediary）。 */
    private static MappingsDecision unfixable(MappingsRequest request, String reason) {
        return new MappingsDecision(request.mode(), request.modNamespace(), null, false, false, request.fabric(),
                List.of(reason + "；本次只比库层"),
                List.of("显式请求的命名空间在该版本组合下不可达"));
    }

    private static String variantOf(MinecraftNamespace target) {
        return target == MinecraftNamespace.MOJANG ? MOJANG_VARIANT : INTERMEDIARY_VARIANT;
    }
}
