package xland.ioutils.jarcompat.mods;

/**
 * 命令行入口。
 *
 * <pre>{@code
 * java -jar build/libs/ModCompat-0.1.0-all.jar mymod.jar -a 1.21.1 -b 1.21.4 --fabric
 * }</pre>
 */
public final class Main {

    private Main() {
    }

    static void main(String[] args) {
        System.exit(ModCompatApp.run(args, System.out, System.err));
    }
}
