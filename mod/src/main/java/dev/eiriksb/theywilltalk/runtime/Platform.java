package dev.eiriksb.theywilltalk.runtime;

import java.util.Locale;

/** The platforms the AI runtime has downloads for. */
public enum Platform {
    LINUX_X64("linux-x64", ""),
    WINDOWS_X64("windows-x64", ".exe"),
    UNSUPPORTED("unsupported", "");

    public final String id;
    /** Executable file suffix. */
    public final String exe;

    Platform(String id, String exe) {
        this.id = id;
        this.exe = exe;
    }

    public static Platform current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (x64 && os.contains("linux")) {
            return LINUX_X64;
        }
        if (x64 && os.contains("windows")) {
            return WINDOWS_X64;
        }
        return UNSUPPORTED;
    }

    /** Fills in {@code {platform}} and {@code {exe}} in a catalogue path. */
    public String expand(String path) {
        return path.replace("{platform}", id).replace("{exe}", exe);
    }
}
