package xland.ioutils.jarcompat.mods.report;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import xland.ioutils.jarcompat.api.CheckReport;
import xland.ioutils.jarcompat.api.Incompatibility;
import xland.ioutils.jarcompat.api.JarCompat;
import xland.ioutils.jarcompat.api.Location;
import xland.ioutils.jarcompat.api.Side;

/**
 * 把 {@link CheckReport} 序列化成 JSON（nanojson）。
 *
 * <p>为什么不用 JarCompat 自带的 {@code report.toJson()} / {@code JarCompat.render(report, JSON)}：
 * 0.1.3 的 JSON 渲染器最后返回的是 {@code JsonStringWriter.toString()}，而 nanojson 的
 * {@code JsonStringWriter} 只在 {@code done()} 里给出结果、没有覆写 {@code toString()}，
 * 于是这两个方法返回的是对象的默认字符串（例如 {@code xland.ioutils.jarcompat.shade.nanojson.JsonStringWriter@1a38c59b}）
 * 而不是 JSON 文本。这里改为用 JarCompat 的公共报告 API（{@link CheckReport} / {@link Incompatibility} /
 * {@link Location} / {@link Side}）自行序列化，字段与 JarCompat 的 JSON 报告保持一致。</p>
 *
 * <p>注意：这只影响“报告呈现”，比较逻辑仍然完全由 {@link JarCompat#check} 完成。</p>
 */
public final class ReportJson {

    private ReportJson() {
    }

    /** 生成与 JarCompat JSON 报告结构一致的 {@link JsonObject}。 */
    public static JsonObject toJsonObject(CheckReport report) {
        JsonObject json = new JsonObject();
        json.put("tool", JarCompat.TOOL_NAME);
        json.put("formatVersion", 1);
        json.put("reachability", report.reachability().name());
        json.put("verdict", report.verdict().name());
        json.put("compatible", report.isCompatible());

        JsonObject summary = new JsonObject();
        summary.put("errors", report.errorCount());
        summary.put("warnings", report.warningCount());
        summary.put("compatibleReferences", report.compatibleReferenceCount());
        summary.put("checkedReferences", report.checkedReferenceCount());
        summary.put("externalReferences", report.externalReferenceCount());
        summary.put("abstractChecks", report.abstractCheckCount());
        summary.put("durationMillis", report.durationMillis());
        json.put("summary", summary);

        json.put("errorsByLibBJar", new JsonObject(report.errorsByLibBJar()));
        json.put("warningsByLibBJar", new JsonObject(report.warningsByLibBJar()));
        json.put("notes", new JsonArray(report.notes()));

        JsonArray items = new JsonArray();
        for (Incompatibility item : report.items()) {
            items.add(item(item));
        }
        json.put("incompatibilities", items);
        return json;
    }

    private static JsonObject item(Incompatibility item) {
        JsonObject json = new JsonObject();
        json.put("severity", item.severity().name());
        json.put("kind", item.kind().name());
        json.put("symbol", item.symbol());
        json.put("occurrences", item.occurrences());
        json.put("expectedError", item.expectedError());
        json.put("expectedMessage", item.expectedMessage());
        json.put("reason", item.reason());
        json.put("suggestion", item.suggestion());

        JsonArray locations = new JsonArray();
        for (Location location : item.locations()) {
            JsonObject entry = new JsonObject();
            entry.put("programJar", location.programJar());
            entry.put("class", location.className());
            entry.put("method", location.methodName());
            entry.put("descriptor", location.methodDescriptor());
            if (location.line() >= 0) {
                entry.put("line", location.line());
            } else {
                entry.put("line", (String) null);
            }
            entry.put("detail", location.detail());
            locations.add(entry);
        }
        json.put("locations", locations);
        json.put("libA", side(item.sideA()));
        json.put("libB", side(item.sideB()));
        return json;
    }

    private static JsonObject side(Side side) {
        if (side == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.put("present", side.present());
        json.put("jar", side.jar());
        json.put("declaration", side.declaration());
        return json;
    }
}
