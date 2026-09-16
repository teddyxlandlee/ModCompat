package xland.ioutils.jarcompat.mods.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * ZIP/JAR 读取小工具：从字节数组里读某个条目（NeoForge 安装包的 {@code /version.json}），
 * 以及校验下载到的文件确实是可读的 ZIP。
 */
public final class Zips {

    private Zips() {
    }

    /**
     * 从 ZIP 字节中读取指定条目。
     *
     * @param archive   ZIP 字节
     * @param entryName 条目名，前导 {@code /} 可省略
     * @return 条目内容
     * @throws ModCompatException 条目不存在或 ZIP 损坏
     */
    public static byte[] readEntry(byte[] archive, String entryName) {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(entryName, "entryName");
        String wanted = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (name.equals(wanted)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new ModCompatException("无法读取 ZIP 条目 " + wanted + ": " + e.getMessage(), e);
        }
        throw new ModCompatException("ZIP 中不存在条目 " + wanted);
    }

    /** 文件是否是当前可读的 ZIP/JAR。 */
    public static boolean isReadableZip(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (ZipFile _ = new ZipFile(file.toFile())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
