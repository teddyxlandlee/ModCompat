package xland.ioutils.jarcompat.mods.core;

import xland.ioutils.jarcompat.mods.ModCompatApp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * 基于 {@link HttpClient}（JDK 自带，无第三方依赖）的 {@link Fetcher} 实现。
 *
 * <p>特性：跟随重定向、连接/请求超时、对可重试错误（IO 异常、HTTP 429/5xx）做指数退避重试，
 * 4xx（除 408/429）直接失败不重试。</p>
 */
public final class HttpFetcher implements Fetcher {

    /** 默认 User-Agent；元数据服务会拒绝空 UA。版本号与 {@code build.gradle.kts} 中的 JarCompat 坐标一致。 */
    public static final String USER_AGENT = "ModCompat/" + ModCompatApp.TOOL_VERSION + " (JarCompat " + ModCompatApp.JAR_COMPAT_VERSION + ")";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int DEFAULT_ATTEMPTS = 3;

    private final HttpClient client;
    private final Duration requestTimeout;
    private final int attempts;
    private final Duration retryBackoff;

    public HttpFetcher() {
        this(DEFAULT_ATTEMPTS, DEFAULT_REQUEST_TIMEOUT, Duration.ofMillis(500));
    }

    public HttpFetcher(int attempts, Duration requestTimeout, Duration retryBackoff) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1");
        }
        this.attempts = attempts;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public byte[] get(String url) throws IOException {
        Objects.requireNonNull(url, "url");
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(requestTimeout)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "*/*")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (!isRetryable(status)) {
                    throw new IOException("HTTP " + status + " " + url);
                }
                last = new IOException("HTTP " + status + " " + url);
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载被中断: " + url, e);
            } catch (IllegalArgumentException e) {
                throw new IOException("非法的 URL: " + url, e);
            }
            if (attempt < attempts) {
                sleep(retryBackoff.multipliedBy(attempt));
            }
        }
        throw last != null ? last : new IOException("无法下载 " + url);
    }

    private static boolean isRetryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("重试等待被中断", e);
        }
    }
}
