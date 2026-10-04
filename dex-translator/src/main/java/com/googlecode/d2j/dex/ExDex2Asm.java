package com.googlecode.d2j.dex;

import com.googlecode.d2j.DexException;
import com.googlecode.d2j.node.DexMethodNode;
import com.googlecode.d2j.util.Constants;
import org.objectweb.asm.AsmBridge;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.MethodNode;

public class ExDex2Asm extends Dex2Asm {

    protected final DexExceptionHandler exceptionHandler;

    public ExDex2Asm(DexExceptionHandler exceptionHandler) {
        this(exceptionHandler, new java.util.Random(0));
    }

    protected ExDex2Asm(DexExceptionHandler exceptionHandler, java.util.Random random) {
        super(random);
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void convertCode(DexMethodNode methodNode, MethodVisitor mv, ClzCtx clzCtx) {
        MethodVisitor mw = AsmBridge.searchMethodWriter(mv);
        MethodNode mn = new MethodNode(Constants.ASM_VERSION, methodNode.access, methodNode.method.getName(),
                methodNode.method.getDesc(), null, null);
        try {
            super.convertCode(methodNode, mn, clzCtx);
        } catch (Exception ex) {
            if (exceptionHandler == null) {
                throw new DexException(ex, "Failed to convert code for %s", methodNode.method);
            } else {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                exceptionHandler.handleMethodTranslateException(methodNode.method, methodNode, mn, ex);
            }
        }
        // code convert ok, copy to MethodWriter and check for Size
        try {
            mn.accept(mv);
        } catch (Exception e) {
            if (exceptionHandler != null) exceptionHandler.handleFileException(e);
            throw new DexException(e, "Failed to emit code for %s", methodNode.method);
        }
        if (mw != null) {
            try {
                AsmBridge.sizeOfMethodWriter(mw);
            } catch (Exception ex) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (exceptionHandler == null) {
                    throw new DexException(ex, "Failed to convert code for %s", methodNode.method);
                } else {
                    exceptionHandler.handleMethodTranslateException(methodNode.method, methodNode, mn, ex);
                }
                AsmBridge.replaceMethodWriter(mw, mn);
            }
        }
    }

}
