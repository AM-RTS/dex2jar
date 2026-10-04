package com.googlecode.d2j.dex;

import com.googlecode.d2j.node.DexFileNode;
import com.googlecode.d2j.node.DexClassNode;
import com.googlecode.d2j.DexException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import com.googlecode.d2j.converter.IR2JConverter;
import com.googlecode.d2j.node.DexMethodNode;
import com.googlecode.d2j.reader.DexFileReader;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.stmt.LabelStmt;
import com.googlecode.dex2jar.ir.stmt.Stmt;
import java.util.Random;
import org.objectweb.asm.MethodVisitor;

/** Shared configured translation pipeline for sequential and parallel callers. */
final class Dex2jarTranslator extends ExDex2Asm {
    private final int readerConfig;
    private final int v3Config;
    private final ExecutorService executor;
    Dex2jarTranslator(DexExceptionHandler handler, int readerConfig, int v3Config, Random random, ExecutorService executor) {
        super(handler, random);
        this.readerConfig = readerConfig;
        this.v3Config = v3Config;
        this.executor = executor;
    }

    public void convertCode(DexMethodNode methodNode, MethodVisitor mv, ClzCtx clzCtx) {
        if ((readerConfig & DexFileReader.SKIP_CODE) != 0 && methodNode.method.getName().equals("<clinit>")) {
            // also skip clinit
            return;
        }
        super.convertCode(methodNode, mv, clzCtx);
    }

    @Override
    public void optimize(IrMethod irMethod) {
        T_CLEAN_LABEL.transform(irMethod);
        T_DEAD_CODE.transform(irMethod);
        T_REMOVE_LOCAL.transform(irMethod);
        T_REMOVE_CONST.transform(irMethod);
        T_ZERO.transform(irMethod);
        if (T_NPE.transformReportChanged(irMethod)) {
            T_DEAD_CODE.transform(irMethod);
            T_REMOVE_LOCAL.transform(irMethod);
            T_REMOVE_CONST.transform(irMethod);
        }
        transformNew(irMethod);
        T_FILL_ARRAY.transform(irMethod);
        T_AGG.transform(irMethod);
        T_MULTI_ARRAY.transform(irMethod);
        T_VOID_INVOKE.transform(irMethod);
        if (0 != (v3Config & V3.PRINT_IR)) {
            int i = 0;
            for (Stmt p : irMethod.stmts) {
                if (p.st == Stmt.ST.LABEL) {
                    LabelStmt labelStmt = (LabelStmt) p;
                    labelStmt.displayName = "L" + i++;
                }
            }
            System.out.println(irMethod);
        }
        {
            // https://github.com/pxb1988/dex2jar/issues/477
            // dead code found in unssa, clean up
            T_DEAD_CODE.transform(irMethod);
            T_REMOVE_LOCAL.transform(irMethod);
            T_REMOVE_CONST.transform(irMethod);
        }
        T_TYPE.transform(irMethod);
        T_UNSSA.transform(irMethod);
        T_IR_2_J_REG_ASSIGN.transform(irMethod);
        T_TRIM_EX.transform(irMethod);
    }

    @Override
    public void ir2j(IrMethod irMethod, MethodVisitor mv, ClzCtx clzCtx) {
        new IR2JConverter()
                .optimizeSynchronized(0 != (V3.OPTIMIZE_SYNCHRONIZED & v3Config))
                .clzCtx(clzCtx)
                .ir(irMethod)
                .asm(mv)
                .convert();
    }

    @Override
    protected void convertClasses(DexFileNode fileNode,
                                  ClassVisitorFactory cvf, Map<String, Clz> classes) {
        if (executor == null) {
            super.convertClasses(fileNode, cvf, classes);
            return;
        }
        List<Future<?>> tasks = new ArrayList<>();
        RuntimeException failure = null;
        try {
            for (DexClassNode node : fileNode.clzs) {
                tasks.add(executor.submit(() -> convertClass(fileNode, node, cvf, classes)));
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        boolean interrupted = false;
        for (Future<?> task : tasks) {
            boolean done = false;
            while (!done) {
                try {
                    task.get();
                    done = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    if (failure == null) failure = e.getCause() instanceof RuntimeException
                            ? (RuntimeException) e.getCause() : new DexException(e.getCause());
                    done = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new DexException("Conversion interrupted");
        }
        if (failure != null) throw failure;
    }
}
