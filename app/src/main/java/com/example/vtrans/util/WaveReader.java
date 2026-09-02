package com.example.vtrans.util;

import android.content.Context;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取 assets 里的 16bit PCM wav。
 * 只处理单声道/立体声 + 16bit 这一种情况，够用且不用引入解码库。
 */
public final class WaveReader {

    public static float[] read(Context ctx, String assetName) throws IOException {
        try (InputStream raw = ctx.getAssets().open(assetName)) {
            return read(raw);
        }
    }

    public static float[] read(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        if (!readFourCc(dis).equals("RIFF")) throw new IOException("不是 RIFF 文件");
        dis.readInt(); // RIFF size
        if (!readFourCc(dis).equals("WAVE")) throw new IOException("不是 WAVE 文件");

        int channels = 1;
        int bits = 16;
        int sampleRate = 16000;
        List<byte[]> chunks = new ArrayList<>();

        while (true) {
            String id;
            try {
                id = readFourCc(dis);
            } catch (EOFException eof) {
                break;
            }
            int size = Integer.reverseBytes(dis.readInt());
            if (size < 0) break;
            if ("fmt ".equals(id)) {
                int fmt = Short.reverseBytes(dis.readShort()) & 0xFFFF;
                channels = Short.reverseBytes(dis.readShort()) & 0xFFFF;
                sampleRate = Integer.reverseBytes(dis.readInt());
                dis.readInt(); // byte rate
                dis.readInt(); // block align 信息见下
                bits = Short.reverseBytes(dis.readShort()) & 0xFFFF;
                if (fmt != 1) throw new IOException("只支持 PCM，实际格式 " + fmt);
                if (bits != 16) throw new IOException("只支持 16bit，实际 " + bits);
            } else if ("data".equals(id)) {
                byte[] buf = new byte[size];
                dis.readFully(buf);
                chunks.add(buf);
            } else {
                // 跳过不认识的 chunk（LIST / fact 之类）
                long skipped = dis.skip(size);
                if (skipped < size) break;
            }
        }

        int total = 0;
        for (byte[] c : chunks) total += c.length;
        ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        for (byte[] c : chunks) bb.put(c);
        bb.flip();

        int frames = total / (2 * channels);
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            float acc = 0;
            for (int c = 0; c < channels; c++) acc += bb.getShort();
            out[i] = (acc / channels) / 32768.0f;
        }
        return out;
    }

    private static String readFourCc(DataInputStream dis) throws IOException {
        byte[] b = new byte[4];
        dis.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
