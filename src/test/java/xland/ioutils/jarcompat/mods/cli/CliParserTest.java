package xland.ioutils.jarcompat.mods.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import xland.ioutils.jarcompat.api.ReachabilityScope;
import xland.ioutils.jarcompat.api.ReportFormat;

class CliParserTest {

    @Test
    @DisplayName("最小合法命令行")
    void parsesMinimalCommandLine() {
        CliOptions options = CliParser.parse(new String[] {"mymod.jar", "-a", "1.21.1", "-b", "1.21.4"});
        assertEquals(Path.of("mymod.jar"), options.program());
        assertEquals("1.21.1", options.mcVersionA());
        assertEquals("1.21.4", options.mcVersionB());
        assertFalse(options.fabric());
        assertFalse(options.neoForge());
        assertEquals(ReportFormat.TEXT, options.format());
        assertEquals(ReachabilityScope.ALL, options.reachability());
        assertEquals("main", options.entryMethod());
        assertNull(options.entryClass());
        assertNotNull(options.cacheDir());
        assertFalse(options.help());
        assertFalse(options.version());
    }

    @Test
    @DisplayName("开关、覆盖版本、长选项等号写法、位置参数在选项之后")
    void parsesFullCommandLine() {
        CliOptions options = CliParser.parse(new String[] {
                "--fabric", "--neoforge",
                "--version-a=1.21.1", "--version-b", "1.21.4",
                "--fabric-override-a", "0.16.9", "--fabric-override-b=0.19.5",
                "--neoforge-override-a", "21.1.250", "--neoforge-override-b=21.4.5",
                "--cache-dir", "build/cache",
                "--format", "json",
                "--reachability", "entry",
                "--entry", "com.example.Mod", "--entry-method", "boot",
                "--output", "report.json",
                "--fail-on-error", "--dry-run",
                "mymod.jar"});
        assertTrue(options.fabric());
        assertTrue(options.neoForge());
        assertEquals("1.21.1", options.mcVersionA());
        assertEquals("1.21.4", options.mcVersionB());
        assertEquals("0.16.9", options.fabricOverrideA());
        assertEquals("0.19.5", options.fabricOverrideB());
        assertEquals("21.1.250", options.neoForgeOverrideA());
        assertEquals("21.4.5", options.neoForgeOverrideB());
        assertEquals(Path.of("build/cache"), options.cacheDir());
        assertEquals(ReportFormat.JSON, options.format());
        assertEquals(ReachabilityScope.ENTRY, options.reachability());
        assertEquals("com.example.Mod", options.entryClass());
        assertEquals("boot", options.entryMethod());
        assertEquals(Path.of("report.json"), options.output());
        assertTrue(options.failOnError());
        assertTrue(options.dryRun());
        assertEquals(Path.of("mymod.jar"), options.program());
    }

    @Test
    @DisplayName("-- 之后的内容一律视为位置参数")
    void supportsDoubleDash() {
        CliOptions options = CliParser.parse(new String[] {"-a", "1.21.1", "-b", "1.21.4", "--", "-weird-name.jar"});
        assertEquals(Path.of("-weird-name.jar"), options.program());
    }

    @Test
    @DisplayName("缺少必填项报错")
    void requiresMandatoryOptions() {
        assertTrue(assertThrows(UsageException.class,
                () -> CliParser.parse(new String[] {"-a", "1.21.1", "-b", "1.21.4"}))
                .getMessage().contains("<program>"));
        assertTrue(assertThrows(UsageException.class,
                () -> CliParser.parse(new String[] {"mymod.jar", "-b", "1.21.4"}))
                .getMessage().contains("--version-a"));
        assertTrue(assertThrows(UsageException.class,
                () -> CliParser.parse(new String[] {"mymod.jar", "-a", "1.21.1"}))
                .getMessage().contains("--version-b"));
        assertThrows(UsageException.class, () -> CliParser.parse(new String[0]));
    }

    @Test
    @DisplayName("override 必须与对应开关同时出现")
    void rejectsOverrideWithoutSwitch() {
        assertTrue(assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--fabric-override-a", "0.16.9"}))
                .getMessage().contains("--fabric"));
        assertTrue(assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--fabric-override-b", "0.16.9"}))
                .getMessage().contains("--fabric"));
        assertTrue(assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--neoforge-override-a", "21.1.250"}))
                .getMessage().contains("--neoforge"));
        assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--neoforge", "--neoforge-override-b", "21.4.5",
                "--fabric-override-a", "0.16.9"}));
    }

    @Test
    @DisplayName("非法取值报错")
    void rejectsInvalidValues() {
        assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--format", "yaml"}));
        assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--reachability", "sometimes"}));
        assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--fabric", "--fabric=1"}));
        assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--cache-dir"}));
        assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "-a", "1.21.1", "-b", "1.21.4", "--unknown"}));
        assertThrows(UsageException.class, () -> CliParser.parse(new String[] {
                "mymod.jar", "other.jar", "-a", "1.21.1", "-b", "1.21.4"}));
    }

    @Test
    @DisplayName("--help / --version 优先于必填校验")
    void helpAndVersionSkipValidation() {
        assertTrue(CliParser.parse(new String[] {"--help"}).help());
        assertTrue(CliParser.parse(new String[] {"-h"}).help());
        assertTrue(CliParser.parse(new String[] {"--version"}).version());
        assertTrue(CliParser.parse(new String[] {"-V"}).version());
        assertTrue(CliParser.usage().contains("--fabric-override-a"));
    }
}
