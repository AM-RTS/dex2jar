package com.googlecode.dex2jar.test;

import com.googlecode.d2j.converter.J2IRConverter;
import com.googlecode.d2j.dex.Dex2Asm;
import com.googlecode.dex2jar.ir.IrMethod;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class PartialMultiArrayTest {
    @Test
    public void preservesDeclaredDimensionsWhenOnlySomeAreAllocated() throws Exception {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()[[[I", null, null);
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.ICONST_3);
        method.visitMultiANewArrayInsn("[[[I", 2);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        IrMethod ir = J2IRConverter.convert("PartialArrayFixture", method);
        Dex2Asm converter = new Dex2Asm();
        converter.optimize(ir);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "PartialArrayFixture", null, "java/lang/Object", null);
        converter.ir2j(ir, writer.visitMethod(method.access, method.name, method.desc, null, null), new Dex2Asm.ClzCtx());
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Class<?> fixture = new ClassLoader() {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        int[][][] value = (int[][][]) fixture.getMethod("value").invoke(null);
        assertEquals(2, value.length);
        assertEquals(3, value[0].length);
        assertNull(value[0][0]);
    }
}
