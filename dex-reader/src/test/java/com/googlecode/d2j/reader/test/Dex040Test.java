package com.googlecode.d2j.reader.test;

import com.googlecode.d2j.DexConstants;
import com.googlecode.d2j.reader.DexFileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class Dex040Test {
    @Test
    public void versionConstantMatchesAsciiMagicAndReader() {
        byte[] magic = "dex\n040\0".getBytes(StandardCharsets.US_ASCII);
        int expected = ByteBuffer.wrap(magic, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertEquals(expected, DexConstants.DEX_040);
        ByteBuffer dex = ByteBuffer.allocate(0x8c).order(ByteOrder.LITTLE_ENDIAN);
        dex.put(magic);
        dex.putInt(0x20, dex.capacity()); // file_size
        dex.putInt(0x24, 0x70); // header_size
        dex.putInt(0x28, 0x12345678); // endian_tag
        dex.putInt(0x34, 0x70); // map_off
        dex.putInt(0x68, 0x1c); // data_size
        dex.putInt(0x6c, 0x70); // data_off
        dex.position(0x70);
        dex.putInt(2); // header_item and map_list
        dex.putShort((short) 0).putShort((short) 0).putInt(1).putInt(0);
        dex.putShort((short) 0x1000).putShort((short) 0).putInt(1).putInt(0x70);
        assertEquals(expected, new DexFileReader(dex.array()).getDexVersion());
    }
}
