package com.googlecode.d2j.util;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveIOTest {
    @TempDir Path dir;
    @Test void outputNamesStayInsideTheirDirectory() throws Exception {
        assertEquals(dir.resolve("a/B.class"), ArchiveIO.resolveFile(dir, "a/B.class"));
        for (String name : new String[]{"", "../escape.class", "a/../../escape.class", "a/./B.class",
                "/escape.class", "C:/escape.class", "a\\..\\escape.class", "a.class:stream", "bad\0.class",
                ".. /escape.class", "a/.../escape.class"}) {
            assertThrows(IOException.class, () -> ArchiveIO.resolveFile(dir, name), name);
        }
        ArchiveIO.writeZip(dir.resolve("valid.jar"), root -> {
            assertEquals(root.resolve("a/B.class"), ArchiveIO.resolveFile(root, "a/B.class"));
            assertThrows(IOException.class, () -> ArchiveIO.resolveFile(root, "../escape.class"));
        });
    }
    @Test void outputNamesRejectDirectoryAndFileSymlinks() throws Exception {
        Path root = Files.createDirectory(dir.resolve("root"));
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Path original = Files.write(outside.resolve("Original.class"), new byte[]{7});
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            Files.createSymbolicLink(root.resolve("Alias.class"), original);
        } catch (UnsupportedOperationException | FileSystemException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlinks unavailable: " + e.getMessage());
        }
        assertThrows(IOException.class, () -> ArchiveIO.resolveFile(root, "link/Original.class"));
        assertThrows(IOException.class, () -> ArchiveIO.resolveFile(root, "Alias.class"));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(original));
    }
    @Test void rawWriterPreservesExistingOutputOnFailure() throws Exception {
        Path output = Files.write(dir.resolve("raw.zip"), new byte[]{1});
        assertThrows(IOException.class, () -> ArchiveIO.writeAtomically(output, staged -> {
            Files.write(staged, new byte[]{2});
            throw new IOException("failed rewrite");
        }));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(output));
        try (java.util.stream.Stream<Path> files = Files.list(dir)) { assertEquals(1, files.count()); }
    }
    @Test void failedWritePreservesDestinationAndCleansStage() throws Exception {
        Path output = dir.resolve("result.jar");
        byte[] original = {1, 2, 3};
        Files.write(output, original);
        assertThrows(IOException.class, () -> ArchiveIO.writeZip(output, root -> {
            Files.write(root.resolve("partial.class"), new byte[]{4});
            throw new IOException("failed write");
        }));
        assertArrayEquals(original, Files.readAllBytes(output));
        try (java.util.stream.Stream<Path> files = Files.list(dir)) { assertEquals(1, files.count()); }
    }
    @Test void failedNewOutputIsNotPublished() throws Exception {
        Path output = dir.resolve("result.jar");
        assertThrows(IllegalStateException.class, () -> ArchiveIO.writeZip(output, root -> {
            throw new IllegalStateException("failed conversion");
        }));
        assertFalse(Files.exists(output));
        try (java.util.stream.Stream<Path> files = Files.list(dir)) { assertEquals(0, files.count()); }
    }
    @Test void successfulArchiveReplacesOutputAfterClose() throws Exception {
        Path output = dir.resolve("result.jar");
        Files.write(output, new byte[]{1});
        ArchiveIO.writeZip(output, root -> Files.write(root.resolve("ok.class"), new byte[]{2, 3}));
        try (FileSystem fs = ArchiveIO.openZip(output)) {
            assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(fs.getPath("/ok.class")));
        }
    }
}
