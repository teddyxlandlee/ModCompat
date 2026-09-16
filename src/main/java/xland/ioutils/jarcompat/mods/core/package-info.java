/**
 * 基础设施：HTTP 传输（{@code Fetcher}/{@code HttpFetcher}）、下载缓存（{@code JarCache}）、
 * 元数据客户端（{@code MetaClient}）、Maven 坐标与 ZIP 小工具。
 *
 * <p>Nullness：本包是 {@link org.jspecify.annotations.NullMarked}；除非显式标注
 * {@link org.jspecify.annotations.Nullable}，所有类型使用均为非空。第三方 JSON 库
 * （nanojson）未标注，因此它的 {@code getString/getObject/getArray} 返回值在本包内按可空处理。</p>
 */
@NullMarked
package xland.ioutils.jarcompat.mods.core;

import org.jspecify.annotations.NullMarked;
