/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 * Licensed under GPL-3.0
 */
package wtf.walrus.ml;

import wtf.walrus.data.TickData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class FlatBufferDeserializer {

    public static List<TickData> deserialize(ByteBuffer buf) {
        List<TickData> ticks = new ArrayList<>();
        if (buf == null || !buf.hasRemaining()) return ticks;

        try {
            buf.order(ByteOrder.LITTLE_ENDIAN);

            int rootOffset    = buf.getInt(0);
            int seqTablePos   = rootOffset;
            int vTableOffset  = buf.getInt(seqTablePos);
            int vTablePos     = seqTablePos - vTableOffset;
            int fieldOffset   = getVTableField(buf, vTablePos, 4);

            if (fieldOffset == 0) return ticks;

            int ticksVectorRel = buf.getInt(seqTablePos + fieldOffset);
            int ticksVectorPos = seqTablePos + fieldOffset + ticksVectorRel;
            int count          = buf.getInt(ticksVectorPos);

            for (int i = 0; i < count; i++) {
                int tickOffset   = buf.getInt(ticksVectorPos + 4 + (i * 4));
                int tickTablePos = ticksVectorPos + 4 + (i * 4) + tickOffset;
                ticks.add(readTickData(buf, tickTablePos));
            }
        } catch (Exception e) {
            if (!(e instanceof IndexOutOfBoundsException)) e.printStackTrace();
        }

        return ticks;
    }

    private static TickData readTickData(ByteBuffer buf, int tablePos) {
        int vTableOffset = buf.getInt(tablePos);
        int vTablePos    = tablePos - vTableOffset;

        float dYaw   = readFloatField(buf, tablePos, vTablePos, 4);
        float dPitch = readFloatField(buf, tablePos, vTablePos, 6);
        float aYaw   = readFloatField(buf, tablePos, vTablePos, 8);
        float aPitch = readFloatField(buf, tablePos, vTablePos, 10);
        float jYaw   = readFloatField(buf, tablePos, vTablePos, 12);
        float jPitch = readFloatField(buf, tablePos, vTablePos, 14);
        float gYaw   = readFloatField(buf, tablePos, vTablePos, 16);
        float gPitch = readFloatField(buf, tablePos, vTablePos, 18);

        return new TickData(dYaw, dPitch, aYaw, aPitch, jYaw, jPitch, gYaw, gPitch);
    }

    private static int getVTableField(ByteBuffer buf, int vTablePos, int slot) {
        int vTableSize = buf.getShort(vTablePos) & 0xFFFF;
        if (slot >= vTableSize) return 0;
        return buf.getShort(vTablePos + slot) & 0xFFFF;
    }

    private static float readFloatField(ByteBuffer buf, int tablePos, int vTablePos, int slot) {
        int fieldOffset = getVTableField(buf, vTablePos, slot);
        if (fieldOffset == 0) return 0.0f;
        return buf.getFloat(tablePos + fieldOffset);
    }
}