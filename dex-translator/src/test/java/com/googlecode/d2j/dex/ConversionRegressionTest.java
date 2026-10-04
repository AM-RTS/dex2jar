package com.googlecode.d2j.dex;

import com.googlecode.d2j.asm.LdcOptimizeAdapter;
import com.googlecode.d2j.node.DexFileNode;
import com.googlecode.d2j.reader.BaseDexFileReader;
import com.googlecode.d2j.visitors.DexFileVisitor;
import com.googlecode.d2j.smali.Smali;
import com.googlecode.d2j.util.ArchiveIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversionRegressionTest {
    @TempDir Path dir;
    @Test void negativeZeroRetainsRawBitsAndReciprocal() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "SignedZero", null, "java/lang/Object", null);
        for (boolean wide : new boolean[]{false, true}) {
            MethodVisitor mv = new LdcOptimizeAdapter(writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    wide ? "doubleZero" : "floatZero", wide ? "()D" : "()F", null, null));
            if (wide) mv.visitLdcInsn(-0.0D); else mv.visitLdcInsn(-0.0F);
            mv.visitInsn(wide ? Opcodes.DRETURN : Opcodes.FRETURN);
            mv.visitMaxs(0, 0); mv.visitEnd();
        }
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Class<?> fixture = new ClassLoader() {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        float f = (Float) fixture.getMethod("floatZero").invoke(null);
        double d = (Double) fixture.getMethod("doubleZero").invoke(null);
        assertEquals(Float.floatToRawIntBits(-0.0F), Float.floatToRawIntBits(f));
        assertEquals(Double.doubleToRawLongBits(-0.0D), Double.doubleToRawLongBits(d));
        assertEquals(Float.NEGATIVE_INFINITY, 1.0F / f);
        assertEquals(Double.NEGATIVE_INFINITY, 1.0D / d);
    }
    @Test void unresolvedFrameHierarchyUsesRealObject() {
        DexHierarchy hierarchy = new DexHierarchy(new DexFileNode());
        assertEquals("java/lang/Object", hierarchy.getCommonSuperClass("missing/A", "missing/B"));
        DexFileNode file = new DexFileNode();
        Smali.smaliFile("A", ".class public Llocal/A;\n.super Llocal/B;\n", file);
        Smali.smaliFile("B", ".class public Llocal/B;\n.super Ljava/lang/Object;\n", file);
        hierarchy = new DexHierarchy(file);
        assertEquals("local/B", hierarchy.getCommonSuperClass("local/A", "local/B"));
        assertEquals("local/B", hierarchy.getCommonSuperClass("local/B", "local/A"));
    }
    @Test void unresolvedFrameFallbackProducesVerifiableClasses() throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        for (String name : new String[]{"missing/A", "missing/B"}) {
            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
            MethodVisitor ctor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd(); w.visitEnd();
            classes.put(name.replace('/', '.'), w.toByteArray());
        }
        DexHierarchy writer = new DexHierarchy(new DexFileNode());
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "FrameFallback", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick",
                "(Z)Ljava/lang/Object;", null, null);
        Label other = new Label(), join = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0); method.visitJumpInsn(Opcodes.IFEQ, other);
        method.visitTypeInsn(Opcodes.NEW, "missing/A"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "missing/A", "<init>", "()V", false);
        method.visitJumpInsn(Opcodes.GOTO, join); method.visitLabel(other);
        method.visitTypeInsn(Opcodes.NEW, "missing/B"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "missing/B", "<init>", "()V", false);
        method.visitLabel(join); method.visitInsn(Opcodes.ARETURN); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        classes.put("FrameFallback", writer.toByteArray());
        ClassLoader loader = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        java.lang.reflect.Method pick = loader.loadClass("FrameFallback").getMethod("pick", boolean.class);
        assertEquals("missing.A", pick.invoke(null, true).getClass().getName());
        assertEquals("missing.B", pick.invoke(null, false).getClass().getName());
    }
    @Test void fatalReadPreservesExistingArchive() throws Exception {
        Path output = dir.resolve("result.jar");
        byte[] original = {1, 2, 3}; Files.write(output, original);
        BaseDexFileReader bad = new NodeReader(new DexFileNode()) {
            @Override public void accept(DexFileVisitor visitor, int config) {
                assertEquals(0, config & com.googlecode.d2j.reader.DexFileReader.IGNORE_READ_EXCEPTION);
                throw new IllegalArgumentException("bad dex");
            }
        };
        assertThrows(RuntimeException.class, () -> Dex2jar.from(bad).withExceptionHandler(new BaseDexExceptionHandler()).to(output));
        assertArrayEquals(original, Files.readAllBytes(output));
    }
    @Test void parallelAndSequentialConversionProduceSameClasses() throws Exception {
        DexFileNode file = simpleFile();
        Path sequential = dir.resolve("sequential.jar"), parallel = dir.resolve("parallel.jar");
        Dex2jar.from(new NodeReader(file)).to(sequential);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try { Dex2jar.from(new NodeReader(file)).withExecutor(workers).to(parallel); }
        finally { workers.shutdown(); }
        try (FileSystem a = ArchiveIO.openZip(sequential); FileSystem b = ArchiveIO.openZip(parallel)) {
            for (String name : new String[]{"A", "B"}) {
                assertArrayEquals(Files.readAllBytes(a.getPath("/" + name + ".class")),
                        Files.readAllBytes(b.getPath("/" + name + ".class")));
            }
        }
    }
    @Test void rejectedWorkerPreservesOutput() throws Exception {
        Path output = dir.resolve("result.jar"); byte[] original = {1}; Files.write(output, original);
        ExecutorService workers = Executors.newSingleThreadExecutor(); workers.shutdown();
        assertThrows(RejectedExecutionException.class,
                () -> Dex2jar.from(new NodeReader(simpleFile())).withExecutor(workers).to(output));
        assertArrayEquals(original, Files.readAllBytes(output));
    }
    @Test void randomConfigurationIsIsolatedBetweenJobs() throws Exception {
        Dex2jar first = Dex2jar.from(new NodeReader(arrayFile())).setRandom(new Random(17));
        Dex2jar second = Dex2jar.from(new NodeReader(arrayFile())).setRandom(new Random(99));
        Path a = dir.resolve("a.jar"), b = dir.resolve("b.jar"), expected = dir.resolve("expected.jar");
        second.to(b); first.to(a);
        Dex2jar.from(new NodeReader(arrayFile())).setRandom(new Random(17)).to(expected);
        try (FileSystem fa = ArchiveIO.openZip(a); FileSystem fb = ArchiveIO.openZip(b);
             FileSystem fe = ArchiveIO.openZip(expected)) {
            byte[] actual = Files.readAllBytes(fa.getPath("/ArrayFixture.class"));
            assertArrayEquals(Files.readAllBytes(fe.getPath("/ArrayFixture.class")), actual);
            assertFalse(Arrays.equals(Files.readAllBytes(fb.getPath("/ArrayFixture.class")), actual));
        }
    }
    private static DexFileNode arrayFile() {
        DexFileNode file = new DexFileNode();
        com.googlecode.d2j.node.DexClassNode clz = new com.googlecode.d2j.node.DexClassNode(
                Opcodes.ACC_PUBLIC, "LArrayFixture;", "Ljava/lang/Object;", null);
        file.clzs.add(clz);
        com.googlecode.d2j.node.DexMethodNode method = new com.googlecode.d2j.node.DexMethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                new com.googlecode.d2j.Method(clz.className, "value", new String[0], "[B"));
        clz.methods = new ArrayList<>(); clz.methods.add(method);
        com.googlecode.d2j.node.DexCodeNode code = new com.googlecode.d2j.node.DexCodeNode(); method.codeNode = code;
        code.visitRegister(1);
        code.visitConstStmt(com.googlecode.d2j.reader.Op.CONST, 0, 1000);
        code.visitTypeStmt(com.googlecode.d2j.reader.Op.NEW_ARRAY, 0, 0, "[B");
        byte[] bytes = new byte[1000]; Arrays.fill(bytes, (byte) 7);
        code.visitFillArrayDataStmt(com.googlecode.d2j.reader.Op.FILL_ARRAY_DATA, 0, bytes);
        code.visitStmt1R(com.googlecode.d2j.reader.Op.RETURN_OBJECT, 0);
        return file;
    }
    @Test void recoverableMethodFailureProducesThrowingStub() throws Exception {
        DexFileNode file = brokenMethodFile(); Path output = dir.resolve("recovered.jar");
        Dex2jar.from(new NodeReader(file)).withExceptionHandler(new BaseDexExceptionHandler()).to(output);
        byte[] bytes;
        try (FileSystem fs = ArchiveIO.openZip(output)) { bytes = Files.readAllBytes(fs.getPath("/Broken.class")); }
        Class<?> fixture = new ClassLoader() {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        java.lang.reflect.InvocationTargetException error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> fixture.getMethod("value").invoke(null));
        assertTrue(error.getCause() instanceof RuntimeException);
        assertTrue(error.getCause().getMessage().contains("d2j fail translate"));
    }
    @Test void strictMethodFailurePreservesExistingOutput() throws Exception {
        Path output = dir.resolve("strict.jar"); byte[] original = {1}; Files.write(output, original);
        assertThrows(RuntimeException.class, () -> Dex2jar.from(new NodeReader(brokenMethodFile())).to(output));
        assertArrayEquals(original, Files.readAllBytes(output));
    }
    private static DexFileNode brokenMethodFile() {
        DexFileNode file = new DexFileNode();
        Smali.smaliFile("Broken", ".class public LBroken;\n.super Ljava/lang/Object;\n"
                + ".method public static value()Ljava/lang/Object;\n.registers 1\n"
                + "new-instance v0, Lmissing/A;\ninvoke-direct {v0}, Lmissing/B;-><init>()V\n"
                + "return-object v0\n.end method\n", file);
        return file;
    }
    private static DexFileNode simpleFile() {
        DexFileNode file = new DexFileNode();
        for (String name : new String[]{"A", "B"}) Smali.smaliFile(name,
                ".class public L" + name + ";\n.super Ljava/lang/Object;\n"
                + ".method public static value()I\n.registers 1\nconst/4 v0, 1\nreturn v0\n.end method\n", file);
        return file;
    }
    static class NodeReader implements BaseDexFileReader {
        final DexFileNode file;
        NodeReader(DexFileNode file) { this.file = file; }
        public int getDexVersion() { return file.dexVersion; }
        public List<String> getClassNames() { List<String> names = new ArrayList<>(); file.clzs.forEach(c -> names.add(c.className)); return names; }
        public void accept(DexFileVisitor visitor) { file.accept(visitor); }
        public void accept(DexFileVisitor visitor, int config) { accept(visitor); }
        public void accept(DexFileVisitor visitor, int index, int config) { file.clzs.get(index).accept(visitor); }
    }
}
