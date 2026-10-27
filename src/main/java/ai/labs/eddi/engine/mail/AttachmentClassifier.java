/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.Locale;
import java.util.Set;

/**
 * Says whether an attachment is an executable or an archive, from its name, its
 * declared type and — for executables — its first bytes.
 * <p>
 * Name and type are the sender's claims, so they can only ever add suspicion;
 * the magic-byte check is what catches {@code invoice.pdf} that is really a
 * Windows executable. Macro-enabled Office formats count as executable: they
 * run code when opened, which is the property that matters here.
 */
final class AttachmentClassifier {

    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            "exe", "dll", "com", "scr", "pif", "cpl", "msi", "msp", "mst", "msc", "bat", "cmd", "vb", "vbs", "vbe", "js", "jse",
            "wsf", "wsh", "wsc", "ps1", "psm1", "psd1", "hta", "lnk", "reg", "inf", "jar", "class", "sh", "bash", "zsh", "csh",
            "command", "app", "apk", "ipa", "deb", "rpm", "elf", "bin", "run", "gadget", "application", "appref-ms", "scf",
            "url", "xll", "docm", "dotm", "xlsm", "xltm", "xlam", "pptm", "potm", "ppam", "ppsm", "sldm", "one", "iqy", "slk");

    private static final Set<String> EXECUTABLE_TYPES = Set.of(
            "application/x-msdownload", "application/x-msdos-program", "application/x-ms-installer", "application/x-msi",
            "application/x-ms-shortcut", "application/x-executable", "application/x-elf", "application/x-mach-binary",
            "application/x-sh", "application/x-shellscript", "application/x-csh", "application/x-bat", "application/bat",
            "application/x-javascript", "text/javascript", "application/javascript", "text/vbscript", "application/x-vbscript",
            "application/hta", "application/java-archive", "application/x-java-archive", "application/vnd.android.package-archive",
            "application/vnd.microsoft.portable-executable", "application/x-dosexec", "application/vnd.ms-excel.sheet.macroenabled.12",
            "application/vnd.ms-word.document.macroenabled.12", "application/vnd.ms-powerpoint.presentation.macroenabled.12");

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "zipx", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "lz", "lzma", "zst", "cab", "arj",
            "ace", "lzh", "iso", "img", "dmg", "vhd", "vhdx", "z");

    private static final Set<String> ARCHIVE_TYPES = Set.of(
            "application/zip", "application/x-zip-compressed", "application/x-rar-compressed", "application/vnd.rar",
            "application/x-7z-compressed", "application/x-tar", "application/gzip", "application/x-gzip", "application/x-bzip2",
            "application/x-xz", "application/zstd", "application/vnd.ms-cab-compressed", "application/x-iso9660-image",
            "application/x-apple-diskimage", "application/x-compressed");

    private AttachmentClassifier() {
    }

    static boolean isExecutable(String filename, String contentType, byte[] head) {
        return EXECUTABLE_EXTENSIONS.contains(extension(filename)) || (contentType != null && EXECUTABLE_TYPES.contains(contentType))
                || hasExecutableMagic(head);
    }

    static boolean isArchive(String filename, String contentType) {
        return ARCHIVE_EXTENSIONS.contains(extension(filename)) || (contentType != null && ARCHIVE_TYPES.contains(contentType));
    }

    /**
     * PE ({@code MZ}), ELF, Mach-O (both byte orders, 32 and 64 bit, and fat
     * binaries — {@code CAFEBABE}, which Java class files share and which are
     * executable too) and a {@code #!} script.
     */
    static boolean hasExecutableMagic(byte[] head) {
        if (head == null || head.length < 2) {
            return false;
        }
        if (head[0] == 'M' && head[1] == 'Z') {
            return true;
        }
        if (head[0] == '#' && head[1] == '!') {
            return true;
        }
        if (head.length < 4) {
            return false;
        }
        int magic = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        return magic == 0x7F454C46 // ELF
                || magic == 0xFEEDFACE || magic == 0xFEEDFACF || magic == 0xCEFAEDFE || magic == 0xCFFAEDFE // Mach-O
                || magic == 0xCAFEBABE; // Mach-O fat binary / Java class
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        String trimmed = filename.strip();
        // Trailing dots and spaces are dropped by Windows when the file is saved,
        // so "invoice.exe." runs as invoice.exe.
        while (trimmed.endsWith(".") || trimmed.endsWith(" ")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int dot = trimmed.lastIndexOf('.');
        return dot < 0 ? "" : trimmed.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
