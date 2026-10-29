package com.googlecode.d2j.reader.test;

import com.googlecode.d2j.reader.zip.ZipUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamReadTest {
    @Test void readsExactBytesAndLeavesTheStreamOpen() throws Exception {
        for (int size : new int[]{0, 1, 31, 1023, 1024, 1025, 4097}) {
            byte[] expected = new byte[size];
            for (int i = 0; i < size; i++) expected[i] = (byte) i;
            ByteArrayInputStream input = new ByteArrayInputStream(expected) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    return super.read(bytes, offset, Math.min(length, 3));
                }
                @Override public void close() throws IOException { fail("Reader closed caller's stream"); }
            };
            assertArrayEquals(expected, ZipUtil.toByteArray(input));
        }
    }
}
