package com.googlecode.d2j.util;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveIOTest {
    @TempDir Path dir;
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
