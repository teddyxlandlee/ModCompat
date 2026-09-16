package xland.ioutils.jarcompat.mods.core;

/**
 * ModCompat 的受检错误语义：网络失败、元数据缺失、JSON 解析失败等。
 *
 * <p>由 {@code ModCompatApp} 统一捕获，转换为退出码 {@code 3}（FAILURE）。</p>
 */
public class ModCompatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModCompatException(String message) {
        super(message);
    }

    public ModCompatException(String message, Throwable cause) {
        super(message, cause);
    }
}
