package com.googlecode.dex2jar.test;

import com.googlecode.d2j.dex.Dex2Asm;
import com.googlecode.d2j.node.DexFileNode;
import com.googlecode.d2j.smali.Smali;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.expr.*;
import com.googlecode.dex2jar.ir.stmt.*;
import com.googlecode.dex2jar.ir.ts.NewTransformer;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import static org.junit.jupiter.api.Assertions.*;

public class ConstructorRepairTest {
    static final String BASE = ".class public Lrepair/Base;\n.super Ljava/lang/Object;\n"
            + ".field public static hits:I\n.field public value:J\n.field public fraction:D\n"
            + ".method public constructor <init>(JD)V\n.registers 6\n"
            + "invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
            + "sget v0, Lrepair/Base;->hits:I\nadd-int/lit8 v0, v0, 1\nsput v0, Lrepair/Base;->hits:I\n"
            + "iput-wide p1, p0, Lrepair/Base;->value:J\niput-wide p3, p0, Lrepair/Base;->fraction:D\n"
            + "return-void\n.end method\n";
    static String subclass(String name, String parent, int increment) {
        return ".class public Lrepair/" + name + ";\n.super Lrepair/" + parent + ";\n"
                + ".method public constructor <init>(JD)V\n.registers 6\n"
                + "invoke-direct/range {p0 .. p4}, Lrepair/" + parent + ";-><init>(JD)V\n"
                + "sget v0, Lrepair/Base;->hits:I\nadd-int/lit16 v0, v0, " + increment
                + "\nsput v0, Lrepair/Base;->hits:I\nreturn-void\n.end method\n";
    }
    static DexFileNode fixtureFile() {
        DexFileNode file = new DexFileNode();
        Smali.smaliFile("Base.smali", BASE, file);
        Smali.smaliFile("Middle.smali", subclass("Middle", "Base", 100), file);
        String child = subclass("Child", "Middle", 1000)
                + ".method public constructor <init>(JDLjava/lang/Void;)V\n.registers 7\n"
                + "invoke-direct/range {p0 .. p4}, Lrepair/Child;-><init>(JD)V\n"
                + "new-instance v0, Ljava/lang/IllegalStateException;\n"
                + "invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V\nthrow v0\n.end method\n"
                + ".method public constructor <init>(I)V\n.registers 6\n"
                + "const-wide/16 v0, 7\nconst-wide v2, 0x3ff0000000000000L\n"
                + "invoke-direct {p0, v0, v1, v2, v3}, Lrepair/Child;-><init>(JD)V\nreturn-void\n.end method\n"
                + ".method public constructor <init>(Ljava/lang/String;)V\n.registers 6\n"
                + "const-wide/16 v0, 7\nconst-wide v2, 0x3ff0000000000000L\n"
                + "invoke-direct {p0, v0, v1, v2, v3}, Lrepair/Base;-><init>(JD)V\nreturn-void\n.end method\n";
        Smali.smaliFile("Child.smali", child, file);
        Smali.smaliFile("Factory.smali", ".class public Lrepair/Factory;\n.super Ljava/lang/Object;\n"
                + ".method public static create()Lrepair/Child;\n.registers 5\nnew-instance v0, Lrepair/Child;\n"
                + "const-wide/16 v1, 7\nconst-wide v3, 0x3ff0000000000000L\n"
                + "invoke-direct {v0, v1, v2, v3, v4}, Lrepair/Base;-><init>(JD)V\nreturn-object v0\n.end method\n", file);
        return file;
    }

    static ClassLoader fixture() {
        DexFileNode file = fixtureFile();
        Map<String, ClassWriter> writers = new HashMap<>();
        new Dex2Asm().convertDex(file, name -> {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writers.put(name.replace('/', '.'), writer);
            return writer;
        });
        return new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                ClassWriter writer = writers.get(name);
                if (writer == null) throw new ClassNotFoundException(name);
                byte[] bytes = writer.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    @Test public void allocationKeepsSubclassAndBypassesExistingConstructorsWithWideArguments() throws Exception {
        ClassLoader loader = fixture();
        Object object = loader.loadClass("repair.Factory").getMethod("create").invoke(null);
        assertEquals("repair.Child", object.getClass().getName());
        assertEquals(7L, object.getClass().getField("value").getLong(object));
        assertEquals(1.0, object.getClass().getField("fraction").getDouble(object));
        assertEquals(1, loader.loadClass("repair.Base").getField("hits").getInt(null));
    }

    @Test public void sameClassThisDelegationRetainsSideEffects() throws Exception {
        ClassLoader loader = fixture();
        loader.loadClass("repair.Child").getConstructor(int.class).newInstance(1);
        assertEquals(1101, loader.loadClass("repair.Base").getField("hits").getInt(null));
    }

    @Test public void skippedSuperConstructorUsesBridgeWithoutRunningMiddleConstructor() throws Exception {
        ClassLoader loader = fixture();
        loader.loadClass("repair.Child").getConstructor(String.class).newInstance("skip");
        assertEquals(1, loader.loadClass("repair.Base").getField("hits").getInt(null));
    }

    @Test public void inlineAllocationRetainsAllocatedOwnerAndStandaloneMismatchFails() {
        IrMethod method = new IrMethod(); method.owner = "Lrepair/Factory;"; method.name = "inline";
        method.args = new String[0]; method.ret = "V"; method.isStatic = true;
        method.stmts.add(Stmts.nVoidInvoke(Exprs.nInvokeSpecial(new Value[]{Exprs.nNew("Lrepair/Child;")},
                "Lrepair/Base;", "<init>", new String[0], "V")));
        method.stmts.add(Stmts.nReturnVoid());
        assertThrows(IllegalStateException.class, () -> new NewTransformer().transform(method));
        new NewTransformer().transform(method, (allocated, call) -> Exprs.nInvokeNew(new Value[0], call.getArgs(), allocated));
        assertEquals("Lrepair/Child;", ((InvokeExpr) method.stmts.getFirst().getOp()).getOwner());
    }

    @Test public void missingHierarchyFailsBeforeClassesAreFinished() {
        DexFileNode file = fixtureFile();
        file.clzs.removeIf(node -> node.className.equals("Lrepair/Middle;"));
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new Dex2Asm().convertDex(file,
                name -> new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, new ClassWriter(ClassWriter.COMPUTE_MAXS)) {
                    @Override public void visitEnd() { finished.incrementAndGet(); }
                }));
        assertEquals(0, finished.get());
    }
    @Test public void parallelCoreRetainsConstructorRepairs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        DexFileNode file = fixtureFile();
        com.googlecode.d2j.reader.BaseDexFileReader reader = new com.googlecode.d2j.reader.BaseDexFileReader() {
            public int getDexVersion() { return file.dexVersion; }
            public List<String> getClassNames() { List<String> names = new ArrayList<>(); file.clzs.forEach(c -> names.add(c.className)); return names; }
            public void accept(com.googlecode.d2j.visitors.DexFileVisitor visitor) { file.accept(visitor); }
            public void accept(com.googlecode.d2j.visitors.DexFileVisitor visitor, int config) { accept(visitor); }
            public void accept(com.googlecode.d2j.visitors.DexFileVisitor visitor, int index, int config) { file.clzs.get(index).accept(visitor); }
        };
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.nio.file.Path output = dir.resolve("repair.jar");
        try { com.googlecode.d2j.dex.Dex2jar.from(reader).withExecutor(workers).to(output); }
        finally { workers.shutdown(); }
        Map<String, byte[]> classes = new HashMap<>();
        try (java.nio.file.FileSystem fs = com.googlecode.d2j.util.ArchiveIO.openZip(output)) {
            for (String name : new String[]{"Base", "Middle", "Child", "Factory"})
                classes.put("repair." + name, java.nio.file.Files.readAllBytes(fs.getPath("/repair/" + name + ".class")));
        }
        ClassLoader loader = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Object object = loader.loadClass("repair.Factory").getMethod("create").invoke(null);
        assertEquals("repair.Child", object.getClass().getName());
        assertEquals(7L, object.getClass().getField("value").getLong(object));
        assertEquals(1.0, object.getClass().getField("fraction").getDouble(object));
        assertEquals(1, loader.loadClass("repair.Base").getField("hits").getInt(null));
        loader.loadClass("repair.Child").getConstructor(String.class).newInstance("skip");
        assertEquals(2, loader.loadClass("repair.Base").getField("hits").getInt(null));
    }
}
