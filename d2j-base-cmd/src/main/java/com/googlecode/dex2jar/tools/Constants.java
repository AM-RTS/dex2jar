package com.googlecode.dex2jar.tools;

/** Compatibility aliases; engine constants live in the reader API. */
public final class Constants {
    private Constants() { throw new UnsupportedOperationException(); }
    public static final int[] JAVA_VERSIONS = com.googlecode.d2j.util.Constants.JAVA_VERSIONS;
    public static final int MAX_JAVA_VERSION = com.googlecode.d2j.util.Constants.MAX_JAVA_VERSION;
    public static final int ASM_VERSION = com.googlecode.d2j.util.Constants.ASM_VERSION;
}
