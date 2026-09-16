package xland.ioutils.jarcompat.mods.cli;

/**
 * ModCompat 的退出码。
 */
public final class ExitCodes {

    /** 比较成功完成（无论结论是否兼容）。 */
    public static final int OK = 0;
    /** 参数错误：缺少必填项、override 与开关冲突、无意义的比较等。 */
    public static final int USAGE = 1;
    /** 发现了确定不兼容项，且指定了 {@code --fail-on-error}。 */
    public static final int INCOMPATIBLE = 2;
    /** 网络、元数据解析、下载或 JarCompat 分析失败。 */
    public static final int FAILURE = 3;

    private ExitCodes() {
    }
}
