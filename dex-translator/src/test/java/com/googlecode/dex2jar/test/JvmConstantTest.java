package com.googlecode.dex2jar.test;

import com.googlecode.d2j.converter.J2IRConverter;
import com.googlecode.d2j.dex.Dex2Asm;
import com.googlecode.dex2jar.ir.IrMethod;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JvmConstantTest {
    @Test
    public void methodHandleLiteralSurvivesRoundTrip() throws Throwable {
        Object value = roundTrip(new Handle(Opcodes.H_INVOKESTATIC, "java/lang/Integer",
                "valueOf", "(I)Ljava/lang/Integer;", false), "Ljava/lang/invoke/MethodHandle;");
        assertTrue(value instanceof MethodHandle);
        assertEquals(17, ((MethodHandle) value).invokeWithArguments(17));
    }

    @Test
    public void methodTypeLiteralSurvivesRoundTrip() throws Exception {
        Object value = roundTrip(Type.getMethodType("(I)Ljava/lang/String;"),
                "Ljava/lang/invoke/MethodType;");
        assertEquals(MethodType.methodType(String.class, int.class), value);
    }

    private static Object roundTrip(Object constant, String returnType) throws Exception {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()" + returnType, null, null);
        method.visitLdcInsn(constant);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        String owner = "JvmConstantFixture";
        IrMethod ir = J2IRConverter.convert(owner, method);
        Dex2Asm converter = new Dex2Asm();
        converter.optimize(ir);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        Dex2Asm.ClzCtx context = new Dex2Asm.ClzCtx();
        context.classDescriptor = "L" + owner + ";";
        converter.ir2j(ir, writer.visitMethod(method.access, method.name, method.desc, null, null), context);
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Class<?> fixture = new ClassLoader() {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return fixture.getMethod("value").invoke(null);
    }
}
