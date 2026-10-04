package com.googlecode.d2j.dex;

import com.googlecode.d2j.Method;
import com.googlecode.d2j.node.DexClassNode;
import com.googlecode.d2j.node.DexFileNode;
import com.googlecode.dex2jar.ir.expr.*;
import java.util.*;
import org.objectweb.asm.*;

/** Per-file repair of DEX constructor bypasses that JVM verification cannot express directly. */
final class ConstructorRepair {
    private final Map<String, DexClassNode> classes = new LinkedHashMap<>();
    private final Map<String, Bridge> bridges = new LinkedHashMap<>();
    ConstructorRepair(DexFileNode file) {
        for (DexClassNode node : file.clzs) classes.put(node.className, node);
    }

    InvokeExpr rewrite(String allocated, String caller, InvokeExpr call) {
        boolean allocation = allocated != null;
        if (allocated == null) {
            DexClassNode node = require(caller);
            // this(...) and ordinary super(...) must retain their original targets.
            if (call.getOwner().equals(caller) || call.getOwner().equals(node.superClass)) return call;
            allocated = node.superClass;
        }
        List<DexClassNode> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String owner = allocated;
        while (!owner.equals(call.getOwner())) {
            if (!visited.add(owner)) throw new IllegalStateException("Cyclic constructor hierarchy: " + owner);
            DexClassNode node = require(owner);
            path.add(node);
            owner = node.superClass;
            if (owner == null) throw new IllegalStateException("Constructor target is not an ancestor: " + call.method);
        }
        if (path.isEmpty()) throw new IllegalStateException("Unexpected empty constructor bridge");
        String key = allocated + "|" + call.method;
        Bridge bridge = bridges.get(key);
        if (bridge == null) {
            String[] signature = Arrays.copyOf(call.getArgs(), call.getArgs().length + 1);
            signature[signature.length - 1] = "Ljava/lang/Void;";
            while (collides(path, signature)) {
                signature = Arrays.copyOf(signature, signature.length + 1);
                signature[signature.length - 1] = "Ljava/lang/Void;";
            }
            int slots = 1;
            for (String argument : signature) slots += Type.getType(argument).getSize();
            if (slots > 255) throw new IllegalStateException("Constructor bridge exceeds JVM parameter limit");
            bridge = new Bridge(path, call.getArgs().clone(), signature);
            bridges.put(key, bridge);
        }
        int extra = bridge.signature.length - call.getArgs().length;
        Value[] arguments = Arrays.copyOf(call.getOps(), call.getOps().length + extra);
        for (int i = call.getOps().length; i < arguments.length; i++) arguments[i] = Exprs.nNull();
        return allocation
                ? Exprs.nInvokeNew(Arrays.copyOfRange(arguments, 1, arguments.length), bridge.signature, allocated)
                : Exprs.nInvokeSpecial(arguments, allocated, "<init>", bridge.signature, "V");
    }

    private DexClassNode require(String owner) {
        DexClassNode node = classes.get(owner);
        if (node == null) throw new IllegalStateException("Missing class for constructor repair: " + owner);
        return node;
    }

    private boolean collides(List<DexClassNode> path, String[] signature) {
        for (DexClassNode node : path) {
            if (node.methods != null) for (com.googlecode.d2j.node.DexMethodNode method : node.methods)
                if (method.method.getName().equals("<init>") && Arrays.equals(method.method.getParameterTypes(), signature)) return true;
            for (Bridge bridge : bridges.values())
                if (bridge.path.contains(node) && Arrays.equals(bridge.signature, signature)) return true;
        }
        return false;
    }

    void emit(Map<String, ClassVisitor> visitors) {
        for (Bridge bridge : bridges.values()) for (DexClassNode node : bridge.path) {
            ClassVisitor visitor = visitors.get(node.className);
            if (visitor == null) throw new IllegalStateException("Class excluded from constructor repair: " + node.className);
            String[] targetArgs = node.superClass.equals(bridge.path.get(bridge.path.size() - 1).superClass)
                    ? bridge.original : bridge.signature;
            MethodVisitor method = visitor.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                    "<init>", new Method(node.className, "<init>", bridge.signature, "V").getDesc(), null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            int slot = 1;
            for (String argument : targetArgs) {
                Type type = Type.getType(argument);
                method.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
                slot += type.getSize();
            }
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, Dex2Asm.toInternalName(node.superClass), "<init>",
                    new Method(node.superClass, "<init>", targetArgs, "V").getDesc(), false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(-1, -1);
            method.visitEnd();
        }
    }

    private static final class Bridge {
        final List<DexClassNode> path;
        final String[] original, signature;
        Bridge(List<DexClassNode> path, String[] original, String[] signature) {
            this.path = path; this.original = original; this.signature = signature;
        }
    }
}
