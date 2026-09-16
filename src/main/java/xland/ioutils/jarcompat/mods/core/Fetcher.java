package xland.ioutils.jarcompat.mods.core;

import java.io.Closeable;
import java.io.IOException;

/**
 * 最小 HTTP 传输抽象：按 URL 取回字节。
 *
 * <p>把网络访问收敛到一个接口，既便于统一加超时/重试，也让整条流水线可以在测试里完全离线运行。</p>
 */
@FunctionalInterface
public interface Fetcher extends Closeable {

    /**
     * 取回 {@code url} 的响应体。
     *
     * <p>本包是 {@code @NullMarked}，因此实现应当返回非空数组；调用方仍会把 {@code null}
     * 视为“获取失败”而不是合法结果（实现可能来自外部）。</p>
     */
    byte[] get(String url) throws IOException;

    @Override
    default void close() throws IOException {
        // 默认无需释放资源
    }
}
