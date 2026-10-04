package com.googlecode.d2j.dex;

import com.googlecode.d2j.DexException;
import com.googlecode.d2j.util.ArchiveIO;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import com.googlecode.d2j.node.DexFileNode;
import com.googlecode.d2j.reader.BaseDexFileReader;
import com.googlecode.d2j.reader.DexFileReader;
import com.googlecode.d2j.reader.MultiDexFileReader;
import com.googlecode.d2j.util.Constants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public final class Dex2jar {

    private Random random = new Random(0);
    private ExecutorService executor;

    /** Caller owns the executor; conversions wait for all submitted class tasks. */
    public Dex2jar withExecutor(ExecutorService executor) {
        this.executor = executor;
        return this;
    }

    private DexExceptionHandler exceptionHandler;

    private final BaseDexFileReader reader;

    private int readerConfig;

    private int v3Config;

    private Dex2jar(BaseDexFileReader reader) {
        super();
        this.reader = reader;
        readerConfig |= DexFileReader.SKIP_DEBUG;
    }

    public void doTranslate(final Path dist) {
        doTranslate(dist, null);
    }

    public void doTranslate(final ByteArrayOutputStream baos) {
        doTranslate(null, baos);
    }

    /**
     * Translates a dex file to a class file and writes it to the specified destination path and stream.
     *
     * @param dist The destination path where the translated class file should be written, or {@code null} if unwanted.
     * @param baos An output stream used for intermediate data storage, or {@code null} if unwanted.
     */
    public void doTranslate(final Path dist, final ByteArrayOutputStream baos) {

        DexFileNode fileNode = new DexFileNode();
        try {
            reader.accept(fileNode, readerConfig);
        } catch (Exception ex) {
            if (exceptionHandler != null) exceptionHandler.handleFileException(ex);
            throw new DexException(ex, "Failed to read dex");
        }

        final Map<String, String> parents = (readerConfig & DexFileReader.COMPUTE_FRAMES) == 0
                ? Collections.emptyMap() : DexHierarchy.parents(fileNode);
        ClassVisitorFactory cvf = new ClassVisitorFactory() {
            @Override
            public ClassVisitor create(final String name) {
                // If we choose to recompute the stack map frames, we need a special impl
                final ClassWriter cw = (readerConfig & DexFileReader.COMPUTE_FRAMES) == 0
                        ? new ClassWriter(ClassWriter.COMPUTE_MAXS)
                        : new DexHierarchy(parents);
                final LambadaNameSafeClassAdapter rca = new LambadaNameSafeClassAdapter(cw,
                        (readerConfig & DexFileReader.DONT_SANITIZE_NAMES) != 0);
                return new ClassVisitor(Constants.ASM_VERSION, rca) {
                    @Override
                    public void visitEnd() {
                        super.visitEnd();
                        String className = rca.getClassName();
                        byte[] data;
                        try {
                            // FIXME handle 'java.lang.RuntimeException: Method code too large!'
                            data = cw.toByteArray();
                        } catch (Exception ex) {
                            System.err.printf("ASM failed to generate .class file: %s%n", className);
                            if (exceptionHandler != null) exceptionHandler.handleFileException(ex);
                            throw new DexException(ex, "Failed to generate %s", className);
                        }
                        try {
                            if (baos != null) {
                                byte[] classNameBytes = className.getBytes(StandardCharsets.UTF_8);
                                baos.write(ByteBuffer.allocate(4).putInt(classNameBytes.length).array());
                                baos.write(classNameBytes);
                                baos.write(ByteBuffer.allocate(4).putInt(data.length).array());
                                baos.write(data);
                            }
                        } catch (IOException e) {
                            if (exceptionHandler != null) exceptionHandler.handleFileException(e);
                            throw new UncheckedIOException(e);
                        }
                        try {
                            if (dist != null) {
                                Path dist1 = dist.resolve(className + ".class");
                                Path parent = dist1.getParent();
                                if (parent != null && !Files.exists(parent)) {
                                    Files.createDirectories(parent);
                                }
                                Files.write(dist1, data);
                            }
                        } catch (IOException e) {
                            if (exceptionHandler != null) exceptionHandler.handleFileException(e);
                            throw new UncheckedIOException(e);
                        }
                    }
                };
            }
        };

        new Dex2jarTranslator(exceptionHandler, readerConfig, v3Config, random, executor)
                .convertDex(fileNode, cvf);

    }

    public DexExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    public BaseDexFileReader getReader() {
        return reader;
    }

    /** @deprecated This option has no effect. */
    @Deprecated
    public Dex2jar reUseReg(boolean b) { return this; }

    /** @deprecated This option has no effect. */
    @Deprecated
    public Dex2jar topoLogicalSort(boolean b) { return this; }

    public Dex2jar noCode(boolean b) {
        if (b) {
            this.readerConfig |= DexFileReader.SKIP_CODE | DexFileReader.KEEP_CLINIT;
        } else {
            this.readerConfig &= ~(DexFileReader.SKIP_CODE | DexFileReader.KEEP_CLINIT);
        }
        return this;
    }

    public Dex2jar optimizeSynchronized(boolean b) {
        if (b) {
            this.v3Config |= V3.OPTIMIZE_SYNCHRONIZED;
        } else {
            this.v3Config &= ~V3.OPTIMIZE_SYNCHRONIZED;
        }
        return this;
    }

    public Dex2jar printIR(boolean b) {
        if (b) {
            this.v3Config |= V3.PRINT_IR;
        } else {
            this.v3Config &= ~V3.PRINT_IR;
        }
        return this;
    }

    /** @deprecated This option has no effect. */
    @Deprecated
    public Dex2jar reUseReg() { return this; }

    public Dex2jar optimizeSynchronized() {
        this.v3Config |= V3.OPTIMIZE_SYNCHRONIZED;
        return this;
    }

    public Dex2jar printIR() {
        this.v3Config |= V3.PRINT_IR;
        return this;
    }

    /** @deprecated This option has no effect. */
    @Deprecated
    public Dex2jar topoLogicalSort() { return this; }

    public void setExceptionHandler(DexExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    public Dex2jar skipDebug(boolean b) {
        if (b) {
            this.readerConfig |= DexFileReader.SKIP_DEBUG;
        } else {
            this.readerConfig &= ~DexFileReader.SKIP_DEBUG;
        }
        return this;
    }

    public Dex2jar skipDebug() {
        this.readerConfig |= DexFileReader.SKIP_DEBUG;
        return this;
    }

    public void to(Path file) throws IOException {
        if (Files.exists(file) && Files.isDirectory(file)) {
            doTranslate(file);
        } else {
            try {
                ArchiveIO.writeZip(file, this::doTranslate);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    public Dex2jar withExceptionHandler(DexExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    public Dex2jar skipExceptions(boolean b) {
        if (b) {
            this.readerConfig |= DexFileReader.SKIP_EXCEPTION;
        } else {
            this.readerConfig &= ~DexFileReader.SKIP_EXCEPTION;
        }
        return this;
    }

    public Dex2jar dontSanitizeNames(boolean b) {
        if (b) {
            this.readerConfig |= DexFileReader.DONT_SANITIZE_NAMES;
        } else {
            this.readerConfig &= ~DexFileReader.DONT_SANITIZE_NAMES;
        }
        return this;
    }

    public Dex2jar computeFrames(boolean b) {
        if (b) {
            this.readerConfig |= DexFileReader.COMPUTE_FRAMES;
        } else {
            this.readerConfig &= ~DexFileReader.COMPUTE_FRAMES;
        }
        return this;
    }

    public Dex2jar setRandom(Random random) {
        this.random = Objects.requireNonNull(random);
        return this;
    }

    public Dex2jar resetRandom() {
        return setRandom(new Random(0));
    }

    public static Dex2jar from(byte[] in) throws IOException {
        return from(MultiDexFileReader.open(in));
    }

    public static Dex2jar from(ByteBuffer in) throws IOException {
        return from(MultiDexFileReader.open(in.array()));
    }

    public static Dex2jar from(BaseDexFileReader reader) {
        return new Dex2jar(reader);
    }

    public static Dex2jar from(File in) throws IOException {
        return from(Files.readAllBytes(in.toPath()));
    }

    public static Dex2jar from(InputStream in) throws IOException {
        return from(MultiDexFileReader.open(in));
    }

    public static Dex2jar from(String in) throws IOException {
        return from(new File(in));
    }

}
