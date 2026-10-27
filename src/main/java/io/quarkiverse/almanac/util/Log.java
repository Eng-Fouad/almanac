package io.quarkiverse.almanac.util;

import java.util.Locale;

/// Minimal logging to stderr (stdout is reserved for command output). Messages use [String#format] syntax
/// with [Locale#ROOT]; [#debug] lines are printed only with `--verbose`.
public final class Log {

    private static volatile boolean verbose;

    private Log() {
    }

    public static void setVerbose(boolean on) {
        verbose = on;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static void info(String format, Object... args) {
        print("", format, args);
    }

    public static void warn(String format, Object... args) {
        print("warning: ", format, args);
    }

    public static void error(String format, Object... args) {
        print("error: ", format, args);
    }

    public static void debug(String format, Object... args) {
        if (verbose) {
            print("  ", format, args);
        }
    }

    private static void print(String prefix, String format, Object... args) {
        String message = args.length == 0 ? format : String.format(Locale.ROOT, format, args);
        System.err.println(prefix + message);
    }
}
