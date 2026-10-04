package com.googlecode.dex2jar.tools;

import com.googlecode.d2j.Method;
import com.googlecode.d2j.node.DexMethodNode;
import com.googlecode.d2j.util.ArchiveIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditRegressionTest {
    @TempDir Path dir;
    public static String decrypt(String text) { return "decoded:" + text; }
    @Test void concurrentFailuresAllAppearInReport() throws Exception {
        BaksmaliBaseDexExceptionHandler handler = new BaksmaliBaseDexExceptionHandler();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                final int index = i;
                tasks.add(workers.submit(() -> {
                    handler.handleFileException(new IllegalArgumentException("file " + index) {
                        @Override public void printStackTrace(java.io.PrintStream stream) { }
                    });
                    Method method = new Method("LFail;", "method" + index, new String[0], "V");
                    handler.handleMethodTranslateException(method, new DexMethodNode(Opcodes.ACC_STATIC, method),
                            new MethodNode(), new IllegalArgumentException("failure " + index));
                }));
            }
            for (Future<?> task : tasks) task.get();
        } finally { workers.shutdown(); }
        Path report = dir.resolve("errors.txt"); handler.dump(report, new String[0]);
        String text = new String(Files.readAllBytes(report), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("There are 200 methods fail to translate."));
        assertTrue(text.contains("There are 200 fails."));
        for (int i = 0; i < 200; i++) assertTrue(text.contains("method" + i + "()V"));
    }
    @Test void extractedDecryptorHandlesStackAndIrModes() throws Exception {
        for (boolean deep : new boolean[]{false, true}) {
            ClassNode node = new ClassNode(); node.version = Opcodes.V1_8; node.access = Opcodes.ACC_PUBLIC;
            node.name = "DecryptFixture"; node.superName = "java/lang/Object";
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
            method.visitLdcInsn("secret");
            method.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(AuditRegressionTest.class),
                    "decrypt", "(Ljava/lang/String;)Ljava/lang/String;", false);
            method.visitInsn(Opcodes.ARETURN); method.visitMaxs(1, 0); method.visitEnd(); node.methods.add(method);
            DecryptStringCmd command = new DecryptStringCmd();
            StringDecryptor.MethodConfig config = command.build("L" + Type.getInternalName(AuditRegressionTest.class)
                    + ";->decrypt(Ljava/lang/String;)Ljava/lang/String;");
            Path emptyJar = dir.resolve("empty" + deep + ".jar"); ArchiveIO.writeZip(emptyJar, root -> {});
            try (DecryptMethodLoader loader = new DecryptMethodLoader(emptyJar, null)) {
                Map<StringDecryptor.MethodConfig, StringDecryptor.MethodConfig> map = loader.loadMethods(Collections.singletonList(config));
                StringDecryptor engine = new StringDecryptor(false, deep, false);
                assertTrue(engine.decrypt(node, map));
                byte[] bytes = engine.toByteArray(node);
                Class<?> fixture = new ClassLoader() {
                    Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
                }.define();
                assertEquals("decoded:secret", fixture.getMethod("value").invoke(null));
            }
        }
    }
    @Test void descriptorParametersUseLoaderAndIgnoreReturnType() throws Exception {
        Path jar = dir.resolve("parameters.jar");
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Parameter", null, "java/lang/Object", null);
        writer.visitEnd();
        ArchiveIO.writeZip(jar, root -> {
            Files.createDirectories(root.resolve("fixture"));
            Files.write(root.resolve("fixture/Parameter.class"), writer.toByteArray());
        });
        try (DecryptMethodLoader loader = new DecryptMethodLoader(jar, null)) {
            Class<?>[] parameters = loader.parameterTypes("(Lfixture/Parameter;[[Ljava/lang/String;[IZBCSIJFD)Lmissing/Return;");
            assertEquals("fixture.Parameter", parameters[0].getName());
            assertArrayEquals(new Class<?>[]{String[][].class, int[].class, boolean.class, byte.class,
                    char.class, short.class, int.class, long.class, float.class, double.class},
                    Arrays.copyOfRange(parameters, 1, parameters.length));
            assertThrows(ClassNotFoundException.class, () -> loader.parameterTypes("([Lmissing/Parameter;)V"));
        }
    }
    @Test void methodKeysRetainEqualityAndHashCalculation() {
        StringDecryptor.MethodConfig a = new StringDecryptor.MethodConfig(), b = new StringDecryptor.MethodConfig();
        assertEquals(a, b); assertEquals(29791, a.hashCode());
        a.desc = b.desc = "(I)V"; a.name = b.name = "decrypt"; a.owner = b.owner = "Fixture";
        int hash = 1; hash = 31 * hash + a.desc.hashCode(); hash = 31 * hash + a.name.hashCode();
        hash = 31 * hash + a.owner.hashCode();
        assertEquals(hash, a.hashCode()); assertEquals(a, b);
        assertNotEquals(a, new StringDecryptor.MethodConfig() {{ desc = "(I)V"; name = "decrypt"; owner = "Fixture"; }});
        b.owner = null; assertNotEquals(a, b);
    }
    @Test void standardCodecMatchesExistingSigningUses() throws Exception {
        Class<?> signer = com.googlecode.d2j.signapk.TinySignImpl.class;
        for (String name : new String[]{"S_PRIVATE_KEY", "S_SIG_PREFIX"}) {
            java.lang.reflect.Field field = signer.getDeclaredField(name); field.setAccessible(true);
            String input = (String)field.get(null);
            assertArrayEquals(com.googlecode.d2j.signapk.Base64.decode(input, 0), java.util.Base64.getDecoder().decode(input));
        }
        for (int length : new int[]{0, 1, 2, 3, 57, 58, 255, 256, 1024}) {
            byte[] bytes = new byte[length]; for (int i = 0; i < length; i++) bytes[i] = (byte)i;
            assertEquals(com.googlecode.d2j.signapk.Base64.encodeToString(bytes, 2), java.util.Base64.getEncoder().encodeToString(bytes));
        }
        assertNotNull(new com.googlecode.d2j.signapk.TinySignImpl());
    }
    @SuppressWarnings("deprecation")
    @Test void legacyOptionsRemainFluentNoOps() throws Exception {
        com.googlecode.d2j.dex.writer.DexFileWriter writer = new com.googlecode.d2j.dex.writer.DexFileWriter();
        com.googlecode.d2j.dex.Dex2jar converter = com.googlecode.d2j.dex.Dex2jar.from(writer.toByteArray());
        assertSame(converter, converter.reUseReg(true)); assertSame(converter, converter.reUseReg());
        assertSame(converter, converter.topoLogicalSort(false)); assertSame(converter, converter.topoLogicalSort());
    }
}
