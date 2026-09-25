package dev.eiriksb.theywilltalk.voice;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnpackTest {
    @TempDir
    Path tmp;

    @Test
    void stripsTheTopFolderAndExcludesWhatIsNotNeeded() throws IOException {
        Path archive = tmp.resolve("kokoro.tar.bz2");
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new BZip2CompressorOutputStream(Files.newOutputStream(archive)))) {
            file(tar, "kokoro-v1/model.onnx", "model", 0644);
            file(tar, "kokoro-v1/espeak-ng-data/en_dict", "dict", 0644);
            file(tar, "kokoro-v1/dict/jieba/big.utf8", "zh", 0644);
            file(tar, "kokoro-v1/date-zh.fst", "fst", 0644);
            file(tar, "kokoro-v1/lexicon-zh.txt", "zh", 0644);
        }
        Path into = tmp.resolve("kokoro");
        int files = Unpack.unpack(options(archive, "tar.bz2", into, "--strip", "1", "--exclude", "dict/**", "--exclude", "*.fst",
                "--exclude", "lexicon-zh.txt"));

        assertEquals(2, files);
        assertEquals("model", Files.readString(into.resolve("model.onnx")));
        assertTrue(Files.exists(into.resolve("espeak-ng-data/en_dict")));
        assertFalse(Files.exists(into.resolve("dict")));
        assertFalse(Files.exists(into.resolve("date-zh.fst")));
        assertFalse(Files.exists(into.resolve("lexicon-zh.txt")));
    }

    @Test
    void flattensSelectedFilesAndRecreatesLibrarySymlinkChains() throws IOException {
        Path archive = tmp.resolve("llama.tar.gz");
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(Files.newOutputStream(archive)))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            file(tar, "llama-b1/build/bin/llama-server", "server", 0755);
            file(tar, "llama-b1/build/bin/llama-cli", "cli", 0755);
            file(tar, "llama-b1/build/bin/libllama.so.0.0.1", "lib", 0644);
            link(tar, "llama-b1/build/bin/libllama.so", "libllama.so.0");  // points at another link
            link(tar, "llama-b1/build/bin/libllama.so.0", "libllama.so.0.0.1");
            link(tar, "llama-b1/build/bin/libggml.so", "../../../../etc/passwd");  // outside: dropped
        }
        Path into = tmp.resolve("llama");
        Unpack.unpack(options(archive, "tar.gz", into, "--flatten", "--include", "llama-server", "--include", "lib*.so*"));

        assertEquals("server", Files.readString(into.resolve("llama-server")));
        assertTrue(Files.isExecutable(into.resolve("llama-server")));
        assertFalse(Files.exists(into.resolve("llama-cli")));
        assertTrue(Files.isSymbolicLink(into.resolve("libllama.so")));
        assertEquals("lib", Files.readString(into.resolve("libllama.so")));
        assertEquals("lib", Files.readString(into.resolve("libllama.so.0")));
        assertFalse(Files.exists(into.resolve("libggml.so"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void unpacksZipFiles() throws IOException {
        Path archive = tmp.resolve("win.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String name : List.of("llama-server.exe", "ggml-cuda.dll", "README.md")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Path into = tmp.resolve("win");
        int files = Unpack.unpack(options(archive, "zip", into, "--flatten", "--include", "llama-server.exe", "--include", "*.dll"));

        assertEquals(2, files);
        assertTrue(Files.exists(into.resolve("ggml-cuda.dll")));
        assertFalse(Files.exists(into.resolve("README.md")));
    }

    @Test
    void refusesEntriesThatEscapeTheTargetFolder() throws IOException {
        Path archive = tmp.resolve("evil.tar.gz");
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(Files.newOutputStream(archive)))) {
            file(tar, "top/../../escaped.txt", "x", 0644);
        }
        assertThrows(IOException.class, () -> Unpack.unpack(options(archive, "tar.gz", tmp.resolve("out"))));
        assertFalse(Files.exists(tmp.resolve("escaped.txt")));
    }

    @Test
    void globsKeepSingleStarsInsideOneFolder() {
        assertTrue(Unpack.glob("lib*.so*").matches("build/bin/libllama.so.0", "libllama.so.0"));
        assertTrue(Unpack.glob("dict/**").matches("dict/a/b.txt", "b.txt"));
        assertFalse(Unpack.glob("dict/*").matches("dict/a/b.txt", "b.txt"));
        assertFalse(Unpack.glob("*.fst").matches("a.fst.txt", "a.fst.txt"));
        assertEquals("a/b", Unpack.relative("./top/a/b", 1));
        assertEquals(null, Unpack.relative("top/", 1));
    }

    private static Unpack.Options options(Path archive, String format, Path into, String... extra) {
        List<String> args = new ArrayList<>(List.of("--archive", archive.toString(), "--format", format, "--into", into.toString()));
        args.addAll(List.of(extra));
        return Unpack.Options.parse(args.toArray(String[]::new));
    }

    private static void file(TarArchiveOutputStream tar, String name, String content, int mode) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry e = new TarArchiveEntry(name, true);
        e.setSize(bytes.length);
        e.setMode(mode);
        tar.putArchiveEntry(e);
        OutputStream out = tar;
        out.write(bytes);
        tar.closeArchiveEntry();
    }

    private static void link(TarArchiveOutputStream tar, String name, String target) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK, true);
        e.setLinkName(target);
        tar.putArchiveEntry(e);
        tar.closeArchiveEntry();
    }
}
