package com.googlecode.d2j.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Map;

/** Shared archive IO, independent of command-line parsing. */
public final class ArchiveIO {
    private ArchiveIO() { }
    public interface FileVisitorX {

        // change the relative from Path to String
        // java.nio.file.ProviderMismatchException on jdk8
        void visitFile(Path file, String relative) throws IOException;

    }

    public static void walkFileTreeX(final Path base, final FileVisitorX fv) throws IOException {
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                fv.visitFile(file, base.relativize(file).toString());
                return super.visitFile(file, attrs);
            }
        });
    }

    public static void walkJarOrDir(final Path in, final FileVisitorX fv) throws IOException {
        if (Files.isDirectory(in)) {
            walkFileTreeX(in, fv);
        } else {
            try (FileSystem inputFileSystem = openZip(in)) {
                walkFileTreeX(inputFileSystem.getPath("/"), fv);
            }
        }
    }

    public static void createParentDirectories(Path p) throws IOException {
        // merge patch from t3stwhat, fix crash on save to windows path like 'C:\\abc.jar'
        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    /** Resolve a slash-separated output name without traversal or existing symbolic links. */
    public static Path resolveFile(Path base, String relative) throws IOException {
        if (relative.isEmpty() || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.indexOf('\0') >= 0) {
            throw new IOException("Invalid output name: " + relative);
        }
        Path root = base.toAbsolutePath().normalize();
        final Path name;
        try { name = root.getFileSystem().getPath(relative); }
        catch (InvalidPathException e) { throw new IOException("Invalid output name: " + relative, e); }
        if (name.isAbsolute()) throw new IOException("Absolute output name: " + relative);
        for (Path part : name) {
            String segment = part.toString();
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IOException("Output traversal: " + relative);
            }
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Output escapes directory: " + relative);
        Path realRoot = Files.exists(root) ? root.toRealPath() : null;
        for (Path path = target; path != null && path.startsWith(root); path = path.getParent()) {
            if (Files.isSymbolicLink(path) || (realRoot != null && Files.exists(path)
                    && !path.toRealPath().startsWith(realRoot))) throw new IOException("Link escapes output path: " + path);
        }
        return target;
    }

    public static FileSystem createZip(Path output) throws IOException {
        Map<String, Object> env = new HashMap<>();
        env.put("create", "true");
        Files.deleteIfExists(output);

        createParentDirectories(output);

        for (FileSystemProvider p : FileSystemProvider.installedProviders()) {
            String s = p.getScheme();
            if ("jar".equals(s) || "zip".equalsIgnoreCase(s)) {
                return p.newFileSystem(output, env);
            }
        }
        throw new IOException("cant find zipfs support");
    }

    public static FileSystem openZip(Path in) throws IOException {
        for (FileSystemProvider p : FileSystemProvider.installedProviders()) {
            String s = p.getScheme();
            if ("jar".equals(s) || "zip".equalsIgnoreCase(s)) {
                return p.newFileSystem(in, new HashMap<>());
            }
        }
        throw new IOException("cant find zipfs support");
    }


    @FunctionalInterface
    public interface ArchiveWriter { void write(Path root) throws IOException; }

    /** Publish only after the staged archive has been written and closed. */
    public static void writeZip(Path output, ArchiveWriter writer) throws IOException {
        writeAtomically(output, staged -> {
            try (FileSystem fs = createZip(staged)) {
                writer.write(fs.getPath("/"));
            }
        });
    }

    /** The callback writes and closes the staged file before it replaces the destination. */
    public static void writeAtomically(Path output, ArchiveWriter writer) throws IOException {
        Path target = output.toAbsolutePath();
        createParentDirectories(target);
        Path staged = Files.createTempFile(target.getParent(), ".d2j-", ".jar");
        try {
            writer.write(staged);
            // Require atomic replacement: unsupported filesystems leave the old output intact.
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
