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
import java.util.Random;
import org.objectweb.asm.MethodVisitor;

/** Shared configured translation pipeline for sequential and parallel callers. */
final class Dex2jarTranslator extends ExDex2Asm {
    private final int readerConfig;
    private final int v3Config;
    private final ExecutorService executor;
    private final Dex2jar.ProgressListener progressListener;
    private int processedClasses;

    Dex2jarTranslator(DexExceptionHandler handler, int readerConfig, int v3Config, Random random,
                      ExecutorService executor, Dex2jar.ProgressListener progressListener) {
        super(handler, random);
        this.readerConfig = readerConfig;
        this.v3Config = v3Config;
        this.executor = executor;
        this.progressListener = progressListener;
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
        super.optimize(irMethod, (v3Config & V3.PRINT_IR) != 0);
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
    public void convertClass(DexFileNode fileNode, DexClassNode node,
                             ClassVisitorFactory cvf, Map<String, Clz> classes) {
        super.convertClass(fileNode, node, cvf, classes);
        if (progressListener != null) {
            synchronized (this) {
                progressListener.onProgressUpdate(fileNode.clzs.size(), ++processedClasses);
            }
        }
    }

    @Override
    protected void convertClasses(DexFileNode fileNode,
                                  ClassVisitorFactory cvf, Map<String, Clz> classes) {
        if (progressListener != null) progressListener.onProgressUpdate(fileNode.clzs.size(), 0);
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
