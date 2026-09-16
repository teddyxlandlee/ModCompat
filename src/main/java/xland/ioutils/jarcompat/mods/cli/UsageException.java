package xland.ioutils.jarcompat.mods.cli;

import java.io.Serial;

/**
 * 命令行参数错误。由 {@code ModCompatApp} 捕获并转换为退出码 {@code 1}（USAGE）。
 */
public class UsageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
        super(message);
    }
}
