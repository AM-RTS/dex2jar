package com.googlecode.d2j.dex;

import com.googlecode.d2j.node.DexFileNode;
import java.util.*;
import org.objectweb.asm.ClassWriter;

/** Frame hierarchy resolution using the input dex before the host classpath. */
final class DexHierarchy extends ClassWriter {
    private final Map<String, String> parents;
    DexHierarchy(DexFileNode file) { this(parents(file)); }
    DexHierarchy(Map<String, String> parents) {
        super(COMPUTE_FRAMES);
        this.parents = parents;
    }
    static Map<String, String> parents(DexFileNode file) {
        Map<String, String> parents = new HashMap<>();
        file.clzs.forEach(c -> { if (c.superClass != null) parents.put(internal(c.className), internal(c.superClass)); });
        return parents;
    }
    private static String internal(String name) {
        return name.startsWith("L") && name.endsWith(";") ? name.substring(1, name.length() - 1) : name;
    }
    @Override protected String getCommonSuperClass(String first, String second) {
        if (first.equals(second)) return first;
        Set<String> ancestors = new HashSet<>();
        String current = first;
        while (current != null && ancestors.add(current)) current = parents.get(current);
        Set<String> visited = new HashSet<>();
        current = second;
        while (current != null && visited.add(current)) {
            if (ancestors.contains(current)) return current;
            current = parents.get(current);
        }
        try {
            return super.getCommonSuperClass(first, second);
        } catch (TypeNotPresentException | LinkageError e) {
            return "java/lang/Object";
        }
    }
}
