package xland.ioutils.jarcompat.mods.core;

import java.nio.charset.StandardCharsets;

/**
 * 在字节序列里找 ASCII 子串。
 *
 * <p>用途只有一个：在 class 文件的常量池里找出 {@code net/minecraft/...} 形式的类名。常量池里的
 * UTF-8 条目是明文（{@code CONSTANT_Utf8} 存的是 modified UTF-8，但 ASCII 部分与普通 UTF-8 一致），
 * 因此不需要解析 class 结构、也不需要引入 ASM 就能得到可靠的答案。</p>
 *
 * <p>用 Boyer–Moore–Horspool 的坏字符规则：模式很短（十几个字节），但常量池有几十 KB，
 * 直接逐字节比较会在每个 class 上白跑一遍。</p>
 */
final class AsciiSearch {

    private AsciiSearch() {
    }

    /**
     * @param data    被搜索的字节
     * @param pattern 要查找的 ASCII 模式（非空）
     * @return {@code data} 中第一次出现的位置；找不到返回 {@code -1}
     */
    static int indexOf(byte[] data, byte[] pattern) {
        return indexOf(data, 0, data.length, pattern);
    }

    /**
     * 在 {@code data[from, to)} 里查找 {@code pattern}。
     *
     * @param data    被搜索的字节
     * @param from    起始下标（含）
     * @param to      结束下标（不含）
     * @param pattern 要查找的 ASCII 模式
     * @return 相对于 {@code data} 起始的绝对下标；找不到返回 {@code -1}
     */
    static int indexOf(byte[] data, int from, int to, byte[] pattern) {
        int n = Math.min(to, data.length);
        int m = pattern.length;
        if (m == 0) {
            return from;
        }
        if (from < 0 || n - from < m) {
            return -1;
        }
        int[] skip = new int[256];
        for (int i = 0; i < 256; i++) {
            skip[i] = m;
        }
        for (int i = 0; i < m - 1; i++) {
            skip[pattern[i] & 0xFF] = m - 1 - i;
        }

        int limit = n - m;
        for (int i = from; i <= limit; ) {
            int j = m - 1;
            while (j >= 0 && data[i + j] == pattern[j]) {
                j--;
            }
            if (j < 0) {
                return i;
            }
            i += skip[data[i + m - 1] & 0xFF];
        }
        return -1;
    }

    /** {@link #indexOf(byte[], byte[])} 的字符串重载。 */
    static int indexOf(byte[] data, String pattern) {
        return indexOf(data, pattern.getBytes(StandardCharsets.US_ASCII));
    }
}
