package xland.ioutils.jarcompat.mods.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import xland.ioutils.jarcompat.api.CheckReport;
import xland.ioutils.jarcompat.api.Incompatibility;
import xland.ioutils.jarcompat.api.IncompatibilityKind;
import xland.ioutils.jarcompat.api.JarCompat;
import xland.ioutils.jarcompat.api.ReportFormat;
import xland.ioutils.jarcompat.api.Severity;
import xland.ioutils.jarcompat.api.Verdict;

/**
 * 守住 ModCompat 依赖的 JarCompat 行为：{@code --format json} 直接嵌入 JarCompat 渲染的报告 JSON，
 * 因此该文本必须是合法 JSON。
 *
 * <p>背景：JarCompat 0.1.3 的 {@code CheckReport.toJson()} / {@code JarCompat.render(report, JSON)}
 * 因为 nanojson {@code JsonStringWriter} 没有覆写 {@code toString()} 而返回对象字符串
 * （例如 {@code ...JsonStringWriter@1a38c59b}），0.1.4 已改为 {@code json.done()}。
 * 这个测试可以在依赖升级时立刻发现同类回归。</p>
 */
class JarCompatJsonTest {

    private static CheckReport sampleReport() {
        return new CheckReport.Builder()
                .reachability(xland.ioutils.jarcompat.api.ReachabilityScope.ALL)
                .verdict(Verdict.INCOMPATIBLE)
                .item(new Incompatibility.Builder()
                        .severity(Severity.ERROR)
                        .kind(IncompatibilityKind.MISSING_METHOD)
                        .symbol("lib/a/Service.greet()Ljava/lang/String;")
                        .occurrences(1)
                        .reason("示例原因")
                        .suggestion("示例建议")
                        .expectedError("java.lang.NoSuchMethodError")
                        .expectedMessage("java.lang.NoSuchMethodError: 'java.lang.String lib.a.Service.greet()'")
                        .build())
                .note("示例备注")
                .compatibleReferences(1)
                .checkedReferences(2)
                .build();
    }

    @Test
    @DisplayName("CheckReport.toJson() 返回合法 JSON，且与 JarCompat.render(report, JSON) 一致")
    void rendersValidJson() throws Exception {
        CheckReport report = sampleReport();

        String json = report.toJson();
        assertTrue(json.startsWith("{"), () -> "toJson() 不是 JSON 对象: " + json);

        JsonObject parsed = JsonParser.object().from(json);
        assertEquals("JarCompat", parsed.getString("tool"));
        assertEquals(1, parsed.getInt("formatVersion"));
        assertEquals("ALL", parsed.getString("reachability"));
        assertEquals("INCOMPATIBLE", parsed.getString("verdict"));
        assertEquals(false, parsed.getBoolean("compatible"));
        assertEquals(1, parsed.getObject("summary").getInt("errors"));
        assertEquals(0, parsed.getObject("summary").getInt("warnings"));
        assertEquals(2, parsed.getObject("summary").getInt("checkedReferences"));

        assertEquals(1, parsed.getArray("incompatibilities").size());
        JsonObject item = parsed.getArray("incompatibilities").getObject(0);
        assertEquals("ERROR", item.getString("severity"));
        assertEquals("MISSING_METHOD", item.getString("kind"));
        assertEquals("lib/a/Service.greet()Ljava/lang/String;", item.getString("symbol"));
        assertEquals("java.lang.NoSuchMethodError", item.getString("expectedError"));
        // kindLabel 由 JarCompat 自己生成，ModCompat 不再复制这套标签
        assertNotNull(item.getString("kindLabel"));

        assertEquals(json, JarCompat.render(report, ReportFormat.JSON));
    }
}
