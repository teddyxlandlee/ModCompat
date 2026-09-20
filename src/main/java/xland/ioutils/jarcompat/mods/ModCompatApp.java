package xland.ioutils.jarcompat.mods;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.api.CheckReport;
import xland.ioutils.jarcompat.api.CheckRequest;
import xland.ioutils.jarcompat.api.JarCompat;
import xland.ioutils.jarcompat.api.JarCompatException;
import xland.ioutils.jarcompat.api.ReachabilityScope;
import xland.ioutils.jarcompat.api.ReportFormat;
import xland.ioutils.jarcompat.mods.cli.CliOptions;
import xland.ioutils.jarcompat.mods.cli.CliParser;
import xland.ioutils.jarcompat.mods.cli.ExitCodes;
import xland.ioutils.jarcompat.mods.cli.UsageException;
import xland.ioutils.jarcompat.mods.core.Fetcher;
import xland.ioutils.jarcompat.mods.core.HttpFetcher;
import xland.ioutils.jarcompat.mods.core.JarCache;
import xland.ioutils.jarcompat.mods.core.MetaClient;
import xland.ioutils.jarcompat.mods.core.MinecraftLayerProbe;
import xland.ioutils.jarcompat.mods.core.ModCompatException;
import xland.ioutils.jarcompat.mods.core.Resource;
import xland.ioutils.jarcompat.mods.core.Zips;
import xland.ioutils.jarcompat.mods.env.BuiltEnvironment;
import xland.ioutils.jarcompat.mods.env.EnvironmentBuilder;
import xland.ioutils.jarcompat.mods.env.EnvironmentFilter;
import xland.ioutils.jarcompat.mods.env.MappingsDecision;
import xland.ioutils.jarcompat.mods.env.MappingsRequest;

/**
 * ModCompat 主流程：解析参数 → 构建两侧环境 → 过滤同坐标资源 → 下载 JAR → 调用 JarCompat → 输出报告。
 *
 * <p>核心比较逻辑完全交给 {@code xland.ioutils:JarCompat:0.1.4} 的公共 API
 * （{@link JarCompat#request()} / {@link JarCompat#check(CheckRequest)}），本项目只负责
 * 环境元数据获取、资源列表构建与结果呈现。</p>
 */
public final class ModCompatApp {

    /** 工具名。 */
    public static final String TOOL_NAME = "ModCompat";

    /** 工具版本。 */
    public static final String TOOL_VERSION = "0.1.0";

    /**
     * 依赖的 JarCompat 版本，与 {@code build.gradle.kts} 中的坐标保持一致。
     *
     * <p>不用 {@link JarCompat#TOOL_VERSION}：它读取的是 JarCompat 包清单里的
     * {@code Implementation-Version}，而 shadow 打包后的 fat JAR 会丢掉这个属性，
     * 于是会退化成 {@code 0.1.0}，显示出来的版本就不对了。</p>
     */
    public static final String JAR_COMPAT_VERSION = "0.1.5";

    private final CliOptions options;
    private final Fetcher fetcher;
    private final PrintStream out;
    private final PrintStream err;

    public ModCompatApp(CliOptions options, Fetcher fetcher, PrintStream out, PrintStream err) {
        this.options = Objects.requireNonNull(options, "options");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    /**
     * 进程入口：解析参数、按需创建 HTTP 下载器并执行比较。
     *
     * @return 进程退出码，见 {@link ExitCodes}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return implRun(args, null, out, err);
    }

    /**
     * 使用自定义 {@link Fetcher} 执行（便于测试，不触发真实网络访问）。
     */
    public static int runWith(String[] args, Fetcher fetcher, PrintStream out, PrintStream err) {
        Objects.requireNonNull(fetcher, "fetcher");
        return implRun(args, fetcher, out, err);
    }

    private static int implRun(String[] args, @Nullable Fetcher fetcher, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        CliOptions options;
        try {
            options = CliParser.parse(args);
        } catch (UsageException e) {
            return usageError(e, err);
        }
        if (options.help()) {
            out.println(CliParser.usage());
            return ExitCodes.OK;
        }
        if (options.version()) {
            out.println(versionLine());
            return ExitCodes.OK;
        }

        if (fetcher == null) {
            try (final Fetcher newFetcher = new HttpFetcher()) {
                return new ModCompatApp(options, newFetcher, out, err).execute();
            } catch (IOException e) {
                err.println("错误: 无法关闭下载器: " + e.getMessage());
                return ExitCodes.FAILURE;
            }
        } else {
            return new ModCompatApp(options, fetcher, out, err).execute();
        }
    }

    /** 执行一次完整比较。 */
    public int execute() {
        Path program = options.program();
        if (!Files.isRegularFile(program)) {
            err.println("参数错误: mod JAR 不存在或不是普通文件: " + program);
            return ExitCodes.USAGE;
        }
        if (!Zips.isReadableZip(program)) {
            // 提前失败：即使 mod JAR 损坏，也不要先下载几百 MB 的上游库
            err.println("参数错误: mod JAR 不是可读取的 ZIP/JAR 文件: " + program);
            return ExitCodes.USAGE;
        }

        // execute() 只会在 implRun 处理完 --help/--version 之后被调用，因此下列字段必定有值
        // （CliOptions 把它们建模为 @Nullable，是因为 help/version 快速路径不会填充它们）。
        // JarCompat 的 CheckRequest.Builder#entryMethod(String) 也是非空契约，这里尽早失败。
        String mcVersionA = Objects.requireNonNull(options.mcVersionA(), "mcVersionA");
        String mcVersionB = Objects.requireNonNull(options.mcVersionB(), "mcVersionB");
        String entryMethod = Objects.requireNonNull(options.entryMethod(), "entryMethod");

        try {
            MetaClient client = new MetaClient(fetcher);
            EnvironmentBuilder builder = new EnvironmentBuilder(client);

            // 命名空间决策必须在建环境之前完成：它决定两侧的 MC 资源用哪个变体。
            // mod JAR 的命名空间只看它自己的常量池，不依赖 loader 参数。
            MappingsDecision mappings = MappingsDecision.evaluate(
                    MappingsRequest.detect(options, mcVersionA, mcVersionB, program));

            BuiltEnvironment environmentA = builder.build("A", mcVersionA, options.fabric(),
                    options.neoForge(), options.fabricOverrideA(), options.neoForgeOverrideA(), mappings);
            BuiltEnvironment environmentB = builder.build("B", mcVersionB, options.fabric(),
                    options.neoForge(), options.fabricOverrideB(), options.neoForgeOverrideB(), mappings);

            if (environmentA.versions().sameEnvironmentAs(environmentB.versions(), options.fabric(), options.neoForge())) {
                err.println("无意义的比较: 两侧环境完全相同（" + environmentA.versions()
                        .describe(options.fabric(), options.neoForge()) + "）。");
                err.println("请至少让 Minecraft 版本或某个已启用 loader 的版本不同（未启用的 loader 不参与判断）。");
                return ExitCodes.USAGE;
            }

            // 同坐标过滤：以对方“未过滤前”的完整坐标集合为基准，保证结果对称
            EnvironmentFilter.Result libA = EnvironmentFilter.filter(environmentA.resources(),
                    EnvironmentFilter.coordsOf(environmentB.resources()));
            EnvironmentFilter.Result libB = EnvironmentFilter.filter(environmentB.resources(),
                    EnvironmentFilter.coordsOf(environmentA.resources()));

            if (options.format() == ReportFormat.JSON) {
                printEnvironmentHeader(err, environmentA, environmentB, libA, libB, mappings);
            } else {
                printEnvironmentHeader(out, environmentA, environmentB, libA, libB, mappings);
            }
            printMappingsWarnings(mappings);

            if (options.dryRun()) {
                if (options.format() == ReportFormat.JSON) {
                    out.println(renderDryRunJson(environmentA, environmentB, libA, libB, mappings));
                } else {
                    printResourceList("lib-a", libA.resources());
                    printResourceList("lib-b", libB.resources());
                }
                return ExitCodes.OK;
            }

            if (libA.resources().isEmpty() || libB.resources().isEmpty()) {
                err.println("无意义的比较: 同坐标过滤后 " + (libA.resources().isEmpty() ? "lib-a" : "lib-b")
                        + " 为空，两侧环境没有可比的上游差异。");
                return ExitCodes.USAGE;
            }

            JarCache cache = new JarCache(options.cacheDir(), fetcher,
                    message -> err.println("  " + message));
            List<Path> libAPaths = localize(cache, "A", libA.resources());
            List<Path> libBPaths = localize(cache, "B", libB.resources());
            err.println("资源就绪: 新下载 " + cache.downloadedCount() + " 个，复用缓存 "
                    + cache.reusedCount() + " 个（缓存目录 " + cache.root() + "）");

            CheckReport report = check(program, libAPaths, libBPaths, entryMethod);
            emit(report, environmentA, environmentB, libA, libB, mappings, libAPaths, libBPaths);
            if (report.errorCount() > 0 && options.failOnError()) {
                return ExitCodes.INCOMPATIBLE;
            }
            return ExitCodes.OK;
        } catch (ModCompatException e) {
            err.println("错误: " + e.getMessage());
            return ExitCodes.FAILURE;
        } catch (JarCompatException e) {
            err.println("JarCompat 分析失败: " + e.getMessage());
            return ExitCodes.FAILURE;
        } catch (IllegalArgumentException e) {
            err.println("参数错误: " + e.getMessage());
            return ExitCodes.USAGE;
        } catch (RuntimeException e) {
            err.println("内部错误: " + e);
            return ExitCodes.FAILURE;
        }
    }

    /** 调用 JarCompat 公共 API 完成比较。 */
    private CheckReport check(Path program, List<Path> libA, List<Path> libB, String entryMethod) {
        CheckRequest.Builder request = JarCompat.request()
                .program(program)
                .libA(libA.toArray(Path[]::new))
                .libB(libB.toArray(Path[]::new))
                .reachability(options.reachability())
                .entryMethod(entryMethod);
        if (options.entryClass() != null) {
            request.entry(options.entryClass());
        }
        if (options.reachability() == ReachabilityScope.ENTRY && options.entryClass() == null) {
            err.println("提示: --reachability entry 会尝试使用 mod JAR 的 Main-Class；mod 通常没有 Main-Class，"
                    + "此时所有引用只会给出 WARN。需要确定性结论请用默认的 --reachability all。");
        }
        return JarCompat.check(request.build());
    }

    /** 下载（或复用缓存）一侧的全部资源，保持 classpath 顺序。 */
    private List<Path> localize(JarCache cache, String side, List<Resource> resources) {
        List<Path> paths = new ArrayList<>(resources.size());
        for (Resource resource : resources) {
            try {
                paths.add(cache.fetch(resource, resource.variant()));
            } catch (IOException e) {
                throw new ModCompatException("环境 " + side + " 的资源 " + resource.displayName()
                        + " 下载失败: " + e.getMessage(), e);
            }
        }
        return paths;
    }

    private void printEnvironmentHeader(PrintStream target, BuiltEnvironment a, BuiltEnvironment b,
                                        EnvironmentFilter.Result libA, EnvironmentFilter.Result libB,
                                        MappingsDecision mappings) {
        target.println(TOOL_NAME + " " + TOOL_VERSION + " — Minecraft mod 双环境兼容性比较（JarCompat "
                + JAR_COMPAT_VERSION + "）");
        target.println("mod JAR   : " + options.program().toAbsolutePath());
        target.println("环境 A    : " + a.versions().describe(options.fabric(), options.neoForge()));
        target.println("环境 B    : " + b.versions().describe(options.fabric(), options.neoForge()));
        target.println("映射      : " + mappings.describe());
        target.println("缓存目录  : " + options.cacheDir().toAbsolutePath());
        target.println("lib-a     : " + describe(libA));
        target.println("lib-b     : " + describe(libB));
        target.println("可达性    : " + (options.reachability() == ReachabilityScope.ALL
                ? "all（全量：program 的每个类/字段/方法都视为入口）"
                : "entry（从入口方法做调用图分析）"));
        target.println();
    }

    private static String describe(EnvironmentFilter.Result result) {
        return result.rawCount() + " 个上游资源 -> 保留 " + result.resources().size()
                + " 个（过滤掉另一侧同坐标 " + result.removedSharedWithOtherSide()
                + " 个，本侧重复坐标 " + result.removedDuplicates() + " 个）";
    }

    private void printResourceList(String label, List<Resource> resources) {
        out.println("== " + label + "（" + resources.size() + " 个）==");
        for (Resource resource : resources) {
            out.println("  - " + resource.displayName() + "  <- " + resource.url());
        }
        out.println();
    }

    /** 把命名空间决策的提示写到 stderr（始终不影响 stdout 的报告本身）。 */
    private void printMappingsWarnings(MappingsDecision mappings) {
        for (String warning : mappings.warnings()) {
            err.println("映射提示: " + warning);
        }
        if (mappings.loaderHeuristic()) {
            err.println("映射提示: 目标命名空间是从唯一启用的 loader 推断的，与 mod 自身的命名空间并不一致；"
                    + "如果不确定该 mod 是在哪个 loader 上构建的，请显式指定 --mappings");
        }
    }

    /** {@code --dry-run --format json}：只输出两侧环境与资源列表，不含报告。 */
    private String renderDryRunJson(BuiltEnvironment a, BuiltEnvironment b,
                                    EnvironmentFilter.Result libA, EnvironmentFilter.Result libB,
                                    MappingsDecision mappings) {
        JsonObject root = new JsonObject();
        root.put("tool", TOOL_NAME);
        root.put("toolVersion", TOOL_VERSION);
        root.put("program", options.program().toAbsolutePath().toString());
        root.put("dryRun", true);
        root.put("mappings", mappingsJson(mappings, null));
        root.put("environmentA", environmentJson(a, libA));
        root.put("environmentB", environmentJson(b, libB));
        return JsonWriter.indent("  ").string().value(root).done();
    }

    private void emit(CheckReport report, BuiltEnvironment a, BuiltEnvironment b,
                      EnvironmentFilter.Result libA, EnvironmentFilter.Result libB,
                      MappingsDecision mappings, List<Path> libAPaths, List<Path> libBPaths) {
        // 兜底断言：一侧真的提供了 Minecraft 类、另一侧一个都没有时，Minecraft 层的结论必然是盲的
        MinecraftLayerProbe.Result mcLayer = MinecraftLayerProbe.probe(libAPaths, libBPaths);
        for (String warning : mcLayer.warnings()) {
            err.println("映射提示: " + warning);
        }

        String rendered;
        if (options.format() == ReportFormat.JSON) {
            rendered = renderJson(report, a, b, libA, libB, mappings, mcLayer);
        } else {
            rendered = report.toText();
        }

        // 结论含不含 Minecraft 层，是本次比较最需要被看清的一件事
        if (options.format() != ReportFormat.JSON) {
            out.println(mcLayerLine(mappings, mcLayer));
        }

        if (options.format() == ReportFormat.JSON) {
            out.println(rendered);
            err.println(verdictLine(report));
        } else {
            out.print(rendered);
            out.println();
            out.println(verdictLine(report));
            if (report.errorCount() > 0 && !options.failOnError()) {
                out.println("提示: 加 --fail-on-error 可在存在确定不兼容项时返回退出码 2。");
            }
        }
        out.flush();

        if (options.output() != null) {
            Path target = options.output().toAbsolutePath();
            try {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, rendered, StandardCharsets.UTF_8);
                err.println("报告已写入 " + target);
            } catch (IOException e) {
                throw new ModCompatException("无法写入报告 " + target + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * 组合输出：环境信息 + JarCompat 报告（置于 {@code report} 字段）。
     *
     * <p>{@code report} 直接采用 JarCompat 自己渲染的 JSON 文本（{@link CheckReport#toJson()}，
     * 等价于 {@code JarCompat.render(report, ReportFormat.JSON)}，0.1.4 起为合法 JSON），
     * 这里仅仅把它解析成 {@link JsonObject} 以便嵌入本工具的外层文档，不做任何字段级改写，
     * 因此报告内容与 {@code JarCompat} CLI 的 {@code --format json} 完全一致。</p>
     */
    private String renderJson(CheckReport report, BuiltEnvironment a, BuiltEnvironment b,
                              EnvironmentFilter.Result libA, EnvironmentFilter.Result libB,
                              MappingsDecision mappings, MinecraftLayerProbe.Result mcLayer) {
        JsonObject root = new JsonObject();
        root.put("tool", TOOL_NAME);
        root.put("toolVersion", TOOL_VERSION);
        root.put("jarCompatVersion", JAR_COMPAT_VERSION);
        root.put("program", options.program().toAbsolutePath().toString());
        root.put("cacheDir", options.cacheDir().toAbsolutePath().toString());
        root.put("reachability", options.reachability().name());
        root.put("mappings", mappingsJson(mappings, mcLayer));
        root.put("environmentA", environmentJson(a, libA));
        root.put("environmentB", environmentJson(b, libB));
        root.put("report", parseJarCompatReport(report));
        return JsonWriter.indent("  ").string().value(root).done();
    }

    /**
     * 命名空间决策的 JSON 表示。
     *
     * <p>{@code mcLayerConclusive} 是这里最要紧的字段：{@code false} 表示报告里的结论只覆盖库层，
     * Minecraft 层的“未发现问题”可能只是引用没解析到。</p>
     *
     * @param mcLayer 兜底探针结果；{@code --dry-run} 时还没有下载资源，传 {@code null}
     */
    private static JsonObject mappingsJson(MappingsDecision mappings, MinecraftLayerProbe.@Nullable Result mcLayer) {
        JsonObject json = new JsonObject();
        json.put("mode", mappings.requestedMode().label());
        json.put("modNamespace", mappings.modNamespace().namespace().name().toLowerCase(Locale.ROOT));
        json.put("modNamespaceDetail", mappings.modNamespace().describe());
        json.put("targetNamespace", mappings.targetNamespace() == null
                ? null : mappings.targetNamespace().label());
        json.put("remapNeeded", mappings.remapNeeded());
        json.put("variant", mappings.variantName());
        json.put("degraded", mappings.degraded());
        json.put("loaderHeuristic", mappings.loaderHeuristic());
        json.put("mcLayerConclusive", mappings.mcLayerConclusive() && (mcLayer == null || mcLayer.conclusive()));
        JsonArray warnings = new JsonArray();
        mappings.warnings().forEach(warnings::add);
        if (mcLayer != null) {
            mcLayer.warnings().forEach(warnings::add);
        }
        json.put("warnings", warnings);
        JsonArray notes = new JsonArray();
        mappings.notes().forEach(notes::add);
        json.put("notes", notes);
        if (mcLayer != null) {
            json.put("minecraftClassesA", mcLayer.classesA());
            json.put("minecraftClassesB", mcLayer.classesB());
        }
        return json;
    }

    /** 文本报告里的“结论覆盖范围”一行。 */
    private static String mcLayerLine(MappingsDecision mappings, MinecraftLayerProbe.Result mcLayer) {
        boolean conclusive = mappings.mcLayerConclusive() && mcLayer.conclusive();
        StringBuilder sb = new StringBuilder("结论范围: ");
        sb.append(conclusive ? "库层 + Minecraft 层" : "仅库层（Minecraft 层未对齐，不可信）");
        sb.append("（Minecraft 类: A 侧 ").append(mcLayer.classesA())
                .append(" 个，B 侧 ").append(mcLayer.classesB()).append(" 个）");
        return sb.toString();
    }

    /**
     * 把 JarCompat 渲染的 JSON 报告文本解析为 {@link JsonObject}，用于嵌入外层文档。
     *
     * <p>只做“解析”，不重新拼装字段；如果 JarCompat 返回的不是合法 JSON，这里会明确报错
     * （退出码 3），而不是悄悄输出一份自制的报告。</p>
     */
    private static JsonObject parseJarCompatReport(CheckReport report) {
        String text = report.toJson();
        try {
            return JsonParser.object().from(text);
        } catch (JsonParserException e) {
            throw new ModCompatException("无法解析 JarCompat " + JAR_COMPAT_VERSION
                    + " 渲染的 JSON 报告: " + e.getMessage(), e);
        }
    }

    private JsonObject environmentJson(BuiltEnvironment environment, EnvironmentFilter.Result result) {
        JsonObject json = new JsonObject();
        json.put("minecraft", environment.versions().minecraftVersion());
        json.put("fabricLoader", environment.versions().fabricLoaderVersion());
        json.put("neoForge", environment.versions().neoForgeVersion());
        json.put("resourceCount", result.resources().size());
        json.put("removedSharedWithOtherSide", result.removedSharedWithOtherSide());
        json.put("removedDuplicates", result.removedDuplicates());
        json.put("coords", result.resources().stream().map(Resource::coords).toList());
        // 与 coords 逐位对应：null 表示该资源没有命名空间变体
        json.put("variants", result.resources().stream().map(Resource::variant).toList());
        json.put("displayNames", result.resources().stream().map(Resource::displayName).toList());
        return json;
    }

    private static String verdictLine(CheckReport report) {
        String verdict = switch (report.verdict()) {
            case COMPATIBLE -> "兼容";
            case INCOMPATIBLE -> "不兼容";
            case UNKNOWN -> "无法判定";
        };
        return "兼容性结论: " + verdict + "（确定不兼容 " + report.errorCount()
                + " 项，潜在不兼容 " + report.warningCount()
                + " 项，未发现问题的引用 " + report.compatibleReferenceCount()
                + " 项，外部引用 " + report.externalReferenceCount()
                + " 项，耗时 " + report.durationMillis() + " ms）";
    }

    private static int usageError(UsageException e, PrintStream err) {
        err.println("参数错误: " + e.getMessage());
        err.println();
        err.println(CliParser.usage());
        return ExitCodes.USAGE;
    }

    private static String versionLine() {
        return TOOL_NAME + " " + TOOL_VERSION + " (JarCompat " + JAR_COMPAT_VERSION + ", "
                + "Java " + Runtime.version().feature() + ")";
    }
}
