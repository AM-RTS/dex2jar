package com.googlecode.dex2jar.tools;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Type;

import com.googlecode.dex2jar.tools.StringDecryptor.MethodConfig;

/** Owns reflective decryptor loading and invocation for one rewrite. */
final class DecryptMethodLoader implements AutoCloseable {
    private final URLClassLoader cl;
    DecryptMethodLoader(Path jar, String classpath) throws IOException {
        List<String> list = new ArrayList<>();
        if (classpath != null) {
            list.addAll(Arrays.asList(classpath.split("[;:]")));
        }
        list.add(jar.toAbsolutePath().toString());
        URL[] urls = new URL[list.size()];
        for (int i = 0; i < list.size(); i++) {
            urls[i] = new File(list.get(i)).toURI().toURL();
        }

        cl = new URLClassLoader(urls);
    }
    @Override public void close() throws IOException { cl.close(); }
    static String invoke(Method method, Object[] args) throws ReflectiveOperationException {
        return (String) method.invoke(null, args);
    }
    Map<MethodConfig, MethodConfig> loadMethods(List<MethodConfig> methodConfigs) throws Exception {
        final Map<MethodConfig, MethodConfig> map = new HashMap<>();
        for (MethodConfig config : methodConfigs) {
            Method jmethod;
            try {
                Class<?> clz = cl.loadClass(config.owner.replace('/', '.'));
                if (clz == null) {
                    System.err.println("clz is null:" + config.owner);
                }
                jmethod = findAnyMethodMatch(clz, config.name,
                        toJavaType(Type.getArgumentTypes(config.desc)));
            } catch (Exception ex) {
                System.err.println("can't load method: L" + config.owner + ";->" + config.name + config.desc);
                throw ex;
            }
            if (jmethod != null) {
                jmethod.setAccessible(true);
                config.jmethod = jmethod;
                map.put(config, config);
            } else {
                throw new NoSuchMethodException("Can't find method " + config.name + config.desc
                        + " on " + config.owner + " or its parent");
            }
        }
        return map;
    }

    private Method findAnyMethodMatch(Class<?> clz, String name, Class<?>[] classes) {
        try {
            return clz.getDeclaredMethod(name, classes);
        } catch (NoSuchMethodException ignored) {
            // https://github.com/pxb1988/dex2jar/issues/51
            // mute exception stack
        }
        Class<?> sup = clz.getSuperclass();
        if (sup != null) {
            Method m = findAnyMethodMatch(sup, name, classes);
            if (m != null) {
                return m;
            }
        }
        Class<?>[] itfs = clz.getInterfaces();
        for (Class<?> itf : itfs) {
            Method m = findAnyMethodMatch(itf, name, classes);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    Class<?>[] toJavaType(Type[] pt) throws ClassNotFoundException {
        Class<?>[] jt = new Class<?>[pt.length];
        for (int i = 0; i < pt.length; i++) {
            jt[i] = toJavaType(pt[i]);
        }
        return jt;
    }

    Class<?> toJavaType(Type t) throws ClassNotFoundException {
        switch (t.getSort()) {
        case Type.BOOLEAN:
            return boolean.class;
        case Type.BYTE:
            return byte.class;
        case Type.SHORT:
            return short.class;
        case Type.CHAR:
            return char.class;
        case Type.INT:
            return int.class;
        case Type.FLOAT:
            return float.class;
        case Type.LONG:
            return long.class;
        case Type.DOUBLE:
            return double.class;
        case Type.OBJECT:
            return Class.forName(t.getClassName());
        case Type.ARRAY:
            return Class.forName(t.getDescriptor());
        case Type.VOID:
            return void.class;
        default:
            break;
        }
        throw new RuntimeException();
    }

}
