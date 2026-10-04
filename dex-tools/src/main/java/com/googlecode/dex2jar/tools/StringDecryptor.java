package com.googlecode.dex2jar.tools;

import com.googlecode.d2j.converter.IR2JConverter;
import com.googlecode.d2j.converter.J2IRConverter;
import com.googlecode.d2j.util.Escape;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.ir.StmtTraveler;
import com.googlecode.dex2jar.ir.expr.Constant;
import com.googlecode.dex2jar.ir.expr.Exprs;
import com.googlecode.dex2jar.ir.expr.FilledArrayExpr;
import com.googlecode.dex2jar.ir.expr.InvokeExpr;
import com.googlecode.dex2jar.ir.expr.Value;
import com.googlecode.dex2jar.ir.ts.AggTransformer;
import com.googlecode.dex2jar.ir.ts.CleanLabel;
import com.googlecode.dex2jar.ir.ts.DeadCodeTransformer;
import com.googlecode.dex2jar.ir.ts.ExceptionHandlerTrim;
import com.googlecode.dex2jar.ir.ts.Ir2JRegAssignTransformer;
import com.googlecode.dex2jar.ir.ts.NewTransformer;
import com.googlecode.dex2jar.ir.ts.NpeTransformer;
import com.googlecode.dex2jar.ir.ts.RemoveConstantFromSSA;
import com.googlecode.dex2jar.ir.ts.RemoveLocalFromSSA;
import com.googlecode.dex2jar.ir.ts.TypeTransformer;
import com.googlecode.dex2jar.ir.ts.UnSSATransformer;
import com.googlecode.dex2jar.ir.ts.VoidInvokeTransformer;
import com.googlecode.dex2jar.ir.ts.ZeroTransformer;
import com.googlecode.dex2jar.ir.ts.array.FillArrayTransformer;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Bytecode rewriting, independent of command-line configuration and class loading. */
final class StringDecryptor {
    private final boolean deleteMethod, deepAnalyze, verbose;
    StringDecryptor(boolean deleteMethod, boolean deepAnalyze, boolean verbose) {
        this.deleteMethod = deleteMethod;
        this.deepAnalyze = deepAnalyze;
        this.verbose = verbose;
    }
    static class MethodConfig {

        Method jmethod;

        /**
         * in java/lang/String format
         */
        String owner;

        String name;

        String desc;

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((desc == null) ? 0 : desc.hashCode());
            result = prime * result + ((name == null) ? 0 : name.hashCode());
            result = prime * result + ((owner == null) ? 0 : owner.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            MethodConfig other = (MethodConfig) obj;
            if (desc == null) {
                if (other.desc != null) {
                    return false;
                }
            } else if (!desc.equals(other.desc)) {
                return false;
            }
            if (name == null) {
                if (other.name != null) {
                    return false;
                }
            } else if (!name.equals(other.name)) {
                return false;
            }
            if (owner == null) {
                return other.owner == null;
            } else {
                return owner.equals(other.owner);
            }
        }
    }

    byte[] toByteArray(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    ClassNode readClassNode(byte[] data) {
        ClassReader cr = new ClassReader(data);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES | ClassReader.SKIP_FRAMES);
        return cn;
    }

    boolean decrypt(ClassNode cn, Map<MethodConfig, MethodConfig> map) {
        if (deepAnalyze) {
            return decryptByIr(cn, map);
        } else {
            return decryptByStack(cn, map);
        }
    }

    private boolean decryptByIr(ClassNode cn, Map<MethodConfig, MethodConfig> map) {
        MethodConfig key = this.key;
        boolean changed = false;
        Iterator<MethodNode> it = cn.methods.iterator();
        while (it.hasNext()) {
            MethodNode m = it.next();
            if (m.instructions == null) {
                continue;
            }
            key.owner = cn.name;
            key.name = m.name;
            key.desc = m.desc;
            if (map.containsKey(key)) {
                if (deleteMethod) {
                    it.remove();
                }
                continue;
            }


            /*if (verbose) {
                System.out.println();
                System.out.println("===============");
                System.out.println("on method " + cn.name + ";->" + m.name + m.desc);
            }*/

            boolean find = false;
            // search for the decrypt method
            for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
                if (p.getOpcode() == Opcodes.INVOKESTATIC) {
                    MethodInsnNode mn = (MethodInsnNode) p;
                    key.owner = mn.owner;
                    key.name = mn.name;
                    key.desc = mn.desc;
                    MethodConfig config = map.get(key);
                    if (config != null) {
                        find = true;
                    }
                }
            }
            if (find) {
                try {
                    // copy m to m2 for cleanup debug info
                    MethodNode m2 = new MethodNode();
                    m2.tryCatchBlocks = new ArrayList<>();
                    m2.name = m.name;
                    m2.access = m.access;
                    m2.desc = m.desc;
                    m.accept(m2);
                    cleanDebug(m2);
                    // convert m2 to ir
                    IrMethod irMethod = J2IRConverter.convert(cn.name, m2);
                    // opt and decrypt
                    optAndDecrypt(irMethod, map);

                    // convert ir to m3
                    MethodNode m3 = new MethodNode();
                    m3.tryCatchBlocks = new ArrayList<>();
                    new IR2JConverter()
                            .ir(irMethod)
                            .asm(m3)
                            .convert();

                    // copy back m3 to m
                    m.maxLocals = -1;
                    m.instructions = m3.instructions;
                    m.tryCatchBlocks = m3.tryCatchBlocks;
                    m.localVariables = null;
                    changed = true;
                } catch (Exception ex) {
                    if (verbose) {
                        ex.printStackTrace();
                    }
                }
            }
        }
        return changed;
    }

    MethodConfig key = new MethodConfig();

    private boolean decryptByStack(ClassNode cn, Map<MethodConfig, MethodConfig> map) {
        MethodConfig key = this.key;
        boolean changed = false;
        Iterator<MethodNode> it = cn.methods.iterator();
        while (it.hasNext()) {
            MethodNode m = it.next();
            if (m.instructions == null) {
                continue;
            }
            key.owner = cn.name;
            key.name = m.name;
            key.desc = m.desc;
            if (map.containsKey(key)) {
                if (deleteMethod) {
                    it.remove();
                }
                continue;
            }

            /*if (verbose) {
                System.out.println();
                System.out.println("===============");
                System.out.println("on method " + cn.name + ";->" + m.name + m.desc);
            }*/

            AbstractInsnNode p = m.instructions.getFirst();
            while (p != null) {
                if (p.getOpcode() == Opcodes.INVOKESTATIC) {
                    MethodInsnNode mn = (MethodInsnNode) p;
                    key.owner = mn.owner;
                    key.name = mn.name;
                    key.desc = mn.desc;
                    MethodConfig config = map.get(key);
                    if (config != null) {
                        //here we are, given that the decryption method is successfully recognised
                        Method jmethod = config.jmethod;
                        try {
                            int pSize = jmethod.getParameterTypes().length;
                            // arguments' list. each parameter's value is retrieved by reading bytecode backwards,
                            // starting from the INVOKESTATIC statement
                            Object[] as = readArgumentValues(mn, jmethod, pSize);
                            if (verbose) {
                                System.out.println(" > calling " + jmethod + " with arguments " + v(as));
                            }
                            //decryption routine invocation
                            String newValue = DecryptMethodLoader.invoke(jmethod, as);
                            if (verbose) {
                                System.out.println("  -> " + Escape.v(newValue));
                            }
                            //LDC statement generation
                            LdcInsnNode nLdc = new LdcInsnNode(newValue);
                            //insertion of the decrypted string's LDC statement, after INVOKESTATIC statement
                            m.instructions.insert(mn, nLdc);
                            //removal of INVOKESTATIC and previous push statements
                            removeInsts(m, mn, pSize);
                            p = nLdc;
                            changed = true;
                        } catch (InvocationTargetException ex) {
                            if (verbose) {
                                ex.getTargetException().printStackTrace();
                            }
                        } catch (Exception ex) {
                            if (verbose) {
                                ex.printStackTrace();
                            }
                        }
                    }
                }
                p = p.getNext();
            }
        }
        return changed;
    }

    protected final CleanLabel tCleanLabel = new CleanLabel();

    protected final Ir2JRegAssignTransformer tIr2JRegAssign = new Ir2JRegAssignTransformer();

    protected final NewTransformer tNew = new NewTransformer();

    protected final RemoveConstantFromSSA tRemoveConst = new RemoveConstantFromSSA();

    protected final RemoveLocalFromSSA tRemoveLocal = new RemoveLocalFromSSA();

    protected final ExceptionHandlerTrim tTrimEx = new ExceptionHandlerTrim();

    protected final TypeTransformer tType = new TypeTransformer();

    protected final DeadCodeTransformer tDeadCode = new DeadCodeTransformer();

    protected final FillArrayTransformer tFillArray = new FillArrayTransformer();

    protected final AggTransformer tAgg = new AggTransformer();

    protected final UnSSATransformer tUnssa = new UnSSATransformer();

    protected final ZeroTransformer tZero = new ZeroTransformer();

    protected final VoidInvokeTransformer tVoidInvoke = new VoidInvokeTransformer();

    protected final NpeTransformer tNpe = new NpeTransformer();

    public void optAndDecrypt(IrMethod irMethod, final Map<MethodConfig, MethodConfig> map) {
        tDeadCode.transform(irMethod);
        tCleanLabel.transform(irMethod);
        tRemoveLocal.transform(irMethod);
        tRemoveConst.transform(irMethod);
        tZero.transform(irMethod);
        if (tNpe.transformReportChanged(irMethod)) {
            tDeadCode.transform(irMethod);
            tRemoveLocal.transform(irMethod);
            tRemoveConst.transform(irMethod);
        }
        tNew.transform(irMethod);
        tFillArray.transform(irMethod);
        tAgg.transform(irMethod);
        tVoidInvoke.transform(irMethod);

        new StmtTraveler() {
            @Override
            public Value travel(Value op) {
                op = super.travel(op);
                if (op.vt == Value.VT.INVOKE_STATIC) {
                    InvokeExpr ie = (InvokeExpr) op;
                    MethodConfig key = StringDecryptor.this.key;
                    key.owner = ie.getOwner().substring(1, ie.getOwner().length() - 1);
                    key.name = ie.getName();
                    key.desc = buildMethodDesc(ie.getArgs(), ie.getRet());

                    MethodConfig c = map.get(key);
                    if (c != null) {
                        try {
                            Method jmethod = c.jmethod;
                            if (ie.getArgs().length != jmethod.getParameterTypes().length) {
                                throw new RuntimeException();
                            }

                            Object[] args = new Object[ie.getArgs().length];
                            for (int i = 0; i < args.length; i++) {
                                args[i] = convertIr2Jobj(ie.getOps()[i], ie.getArgs()[i]);
                            }
                            if (verbose) {
                                System.out.println(" > calling " + jmethod + " with arguments " + v(args));
                            }
                            String str = DecryptMethodLoader.invoke(jmethod, args);
                            if (verbose) {
                                System.out.println("  -> " + Escape.v(str));
                            }
                            return Exprs.nString(str);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                    }
                }
                return op;
            }
        }.travel(irMethod.stmts);

        tType.transform(irMethod);
        tUnssa.transform(irMethod);
        tTrimEx.transform(irMethod);
        tIr2JRegAssign.transform(irMethod);
    }

    public static String v(Object[] vs) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object obj : vs) {
            if (first) {
                first = false;
            } else {
                sb.append(",");
            }
            if (obj instanceof String) {
                sb.append(Escape.v(obj));
            } else {
                sb.append(obj);
            }
        }
        return sb.append("]").toString();
    }

    private Object convertIr2Jobj(Value value, String type) {
        if (value instanceof Constant) {
            if (Constant.NULL.equals(((Constant) value).value)) {
                return null;
            }
        }
        switch (type) {
        case "Z": {
            Object obj = ((Constant) value).value;
            return obj instanceof Boolean ? obj : ((Number) obj).intValue() != 0;
        }
        case "B": {
            Object obj = ((Constant) value).value;
            return ((Number) obj).byteValue();
        }
        case "S": {
            Object obj = ((Constant) value).value;
            return ((Number) obj).shortValue();
        }
        case "C": {
            Object obj = ((Constant) value).value;
            return obj instanceof Character ? obj : (char) ((Number) obj).intValue();
        }
        case "I": {
            Object obj = ((Constant) value).value;
            return ((Number) obj).intValue();
        }
        case "J": {
            Object obj = ((Constant) value).value;
            return ((Number) obj).longValue();
        }
        case "F": {
            Object obj = ((Constant) value).value;
            return obj instanceof Float ? obj : Float.intBitsToFloat(((Number) obj).intValue());
        }
        case "D": {
            Object obj = ((Constant) value).value;
            return obj instanceof Double ? obj : Double.longBitsToDouble(((Number) obj).longValue());
        }
        case "Ljava/lang/String;":
            return ((Constant) value).value;
        case "[Z":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof boolean[]) {
                    return obj;
                } else {

                    boolean[] b = new boolean[Array.getLength(obj)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = ((Number) Array.get(obj, i)).intValue() != 0;
                    }
                    return b;
                }
            } else if (value instanceof FilledArrayExpr) {
                boolean[] b = new boolean[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    if (obj instanceof Boolean) {
                        b[i] = (Boolean) obj;
                    } else {
                        b[i] = ((Number) obj).intValue() != 0;
                    }
                }
                return b;
            }
            throw new RuntimeException();
        case "[B":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof byte[]) {
                    return obj;
                } else {
                    byte[] b = new byte[Array.getLength(obj)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = ((Number) Array.get(obj, i)).byteValue();
                    }
                    return b;
                }
            } else if (value instanceof FilledArrayExpr) {
                byte[] b = new byte[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    b[i] = ((Number) obj).byteValue();
                }
                return b;
            }
            throw new RuntimeException();
        case "[S":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof short[]) {
                    return obj;
                } else {
                    short[] b = new short[Array.getLength(obj)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = ((Number) Array.get(obj, i)).shortValue();
                    }
                    return b;
                }
            } else if (value instanceof FilledArrayExpr) {
                short[] b = new short[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    b[i] = ((Number) obj).shortValue();
                }
                return b;
            }
            throw new RuntimeException();
        case "[C":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof char[]) {
                    return obj;
                } else {
                    char[] b = new char[Array.getLength(obj)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = (char) ((Number) Array.get(obj, i)).intValue();
                    }
                    return b;
                }
            } else if (value instanceof FilledArrayExpr) {
                char[] b = new char[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    b[i] = obj instanceof Character ? (Character) obj : (char) ((Number) obj).intValue();
                }
                return b;
            }
            throw new RuntimeException();
        case "[I":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof int[]) {
                    return obj;
                } else {
                    int[] b = new int[Array.getLength(obj)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = ((Number) Array.get(obj, i)).intValue();
                    }
                    return b;
                }
            } else if (value instanceof FilledArrayExpr) {
                int[] b = new int[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    b[i] = ((Number) obj).intValue();
                }
                return b;
            }
            throw new RuntimeException();
        case "[J":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof long[]) {
                    return obj;
                } else {
                    long[] b = new long[Array.getLength(obj)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = ((Number) Array.get(obj, i)).longValue();
                    }
                    return b;
                }
            } else if (value instanceof FilledArrayExpr) {
                long[] b = new long[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    b[i] = ((Number) obj).longValue();
                }
                return b;
            }
            throw new RuntimeException();
        case "[F":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof float[]) {
                    return obj;
                } else {
                    float[] b = new float[Array.getLength(obj)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = (char) ((Number) Array.get(obj, i)).intValue();
                    }
                    return b;
                }
            } else if (value instanceof FilledArrayExpr) {
                float[] b = new float[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    b[i] = obj instanceof Float
                            ? (Float) obj
                            : Float.intBitsToFloat(((Number) obj).intValue());
                }
                return b;
            }
            throw new RuntimeException();
        case "[D":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof double[]) {
                    return obj;
                } else {
                    double[] b = new double[Array.getLength(obj)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = (char) ((Number) Array.get(obj, i)).intValue();
                    }
                    return b;
                }
            } else if (value instanceof FilledArrayExpr) {
                double[] b = new double[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    b[i] = obj instanceof Double
                            ? (Double) obj
                            : Double.longBitsToDouble(((Number) obj).longValue());
                }
                return b;
            }
            throw new RuntimeException();
        case "[Ljava/lang/String;":
            if (value instanceof Constant) {
                Object obj = ((Constant) value).value;
                if (obj instanceof String[]) {
                    return obj;
                }
            } else if (value instanceof FilledArrayExpr) {
                String[] b = new String[value.getOps().length];
                for (int i = 0; i < b.length; i++) {
                    Object obj = ((Constant) value.getOps()[i]).value;
                    if (obj instanceof String) {
                        b[i] = (String) obj;
                    } else if (Constant.NULL.equals(obj)) {
                        b[i] = null;
                    } else {
                        throw new RuntimeException();
                    }
                }
                return b;
            }
            throw new RuntimeException();
        default:
            break;
        }
        throw new RuntimeException();
    }

    private String buildMethodDesc(String[] args, String ret) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (String s : args) {
            sb.append(s);
        }
        return sb.append(')').append(ret).toString();
    }

    private void cleanDebug(MethodNode mn) {
        AbstractInsnNode p = mn.instructions.getFirst();
        while (p != null) {
            if (p.getType() == AbstractInsnNode.LINE) {
                AbstractInsnNode q = p.getNext();
                mn.instructions.remove(p);
                p = q;
            } else {
                p = p.getNext();
            }
        }
        mn.localVariables = null;
    }

    void removeInsts(MethodNode m, MethodInsnNode mn, int pSize) {
        // remove args
        for (int i = 0; i < pSize; i++) {
            m.instructions.remove(mn.getPrevious());
        }
        // remove INVOKESTATIC
        m.instructions.remove(mn);
    }

    Object[] readArgumentValues(MethodInsnNode mn, Method jmethod, int pSize) {
        AbstractInsnNode q = mn;
        Object[] as = new Object[pSize];
        for (int i = pSize - 1; i >= 0; i--) {
            q = q.getPrevious();
            Object object = readCst(q);
            as[i] = convert(object, jmethod.getParameterTypes()[i]);
        }
        return as;
    }

    Object convert(Object object, Class<?> type) {
        if (int.class.equals(type)) {
            return ((Number) object).intValue();
        }
        if (byte.class.equals(type)) {
            return ((Number) object).byteValue();
        }
        if (short.class.equals(type)) {
            return ((Number) object).shortValue();
        }
        if (char.class.equals(type)) {
            return (char) ((Number) object).intValue();
        }
        if (boolean.class.equals(type)) {
            return (char) ((Number) object).intValue() != 0;
        }
        if (long.class.equals(type)) {
            return (char) ((Number) object).longValue();
        }
        if (float.class.equals(type)) {
            return (char) ((Number) object).floatValue();
        }
        if (double.class.equals(type)) {
            return (char) ((Number) object).doubleValue();
        }
        return object;
    }

    Object readCst(AbstractInsnNode q) {

        switch (q.getOpcode()) {
        case Opcodes.LDC:
            // LDC: String, integer, long and double cases (Opcodes.LDC comprehends LDC_W and LDC2_W)
            // push 32bit or 64bit int/float
            // push string/type
            LdcInsnNode ldc = (LdcInsnNode) q;
            if (ldc.cst instanceof Type) {
                throw new RuntimeException("not support .class value yet!");
            }
            return ldc.cst;

        case Opcodes.BIPUSH:
        case Opcodes.SIPUSH:
            // INT_INSN ("instruction with a single int operand")
            // push 8bit or 16bit int
            IntInsnNode in = (IntInsnNode) q;
            return in.operand;

        case Opcodes.ICONST_M1:
        case Opcodes.ICONST_0:
        case Opcodes.ICONST_1:
        case Opcodes.ICONST_2:
        case Opcodes.ICONST_3:
        case Opcodes.ICONST_4:
        case Opcodes.ICONST_5:
            // ICONST_*: push a tiny int, -1 <= value <= 5
            return q.getOpcode() - Opcodes.ICONST_0;
        case Opcodes.LCONST_0:
        case Opcodes.LCONST_1:
            return (long) (q.getOpcode() - Opcodes.LCONST_0);
        case Opcodes.FCONST_0:
        case Opcodes.FCONST_1:
        case Opcodes.FCONST_2:
            return (float) (q.getOpcode() - Opcodes.FCONST_0);
        case Opcodes.DCONST_0:
        case Opcodes.DCONST_1:
            return (double) (q.getOpcode() - Opcodes.DCONST_0);
        case Opcodes.ACONST_NULL:
            return null;
        default:
            break;
        }

        throw new RuntimeException();
    }

}
