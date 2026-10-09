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
                dis.readInt();   // byte rate
                dis.readShort(); // block align：它只有 2 字节。以前这里读成 int，于是
                                 // bits 实际取到了下一个 chunk 头的 "da"（=24932），
                                 // 好好的 16bit 文件被报成「只支持 16bit，实际 24932」
                bits = Short.reverseBytes(dis.readShort()) & 0xFFFF;
                // WAVE_FORMAT_EXTENSIBLE 的 fmt 是 40 字节，剩下的一律跳过，
                // 否则下一轮会把多出来的字节当成 chunk id 解
                int extra = size - 16;
                if (extra > 0 && !skipFully(dis, extra)) break;
                // 0xFFFE 是 EXTENSIBLE 封装，真正的采样宽度仍看上面的 bits，不是另一种编码
                if (fmt != 1 && fmt != 0xFFFE) throw new IOException("只支持 PCM，实际格式 " + fmt);
                if (bits != 16) throw new IOException("只支持 16bit，实际 " + bits);
            } else if ("data".equals(id)) {
                byte[] buf = new byte[size];
                dis.readFully(buf);
                chunks.add(buf);
            } else {
                // 跳过不认识的 chunk（LIST / fact 之类）
                if (!skipFully(dis, size)) break;
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

    /**
     * 跳够 n 个字节。{@code InputStream.skip()} 不保证一次跳完（从 asset 读时常常
     * 只跳一部分），原实现拿 {@code skipped < size} 直接判失败，会把好文件提前截掉。
     *
     * @return 跳成功返回 true；走到文件尾返回 false
     */
    private static boolean skipFully(DataInputStream dis, long n) {
        while (n > 0) {
            long s;
            try {
                s = dis.skip(n);
            } catch (IOException e) {
                return false;
            }
            if (s > 0) {
                n -= s;
                continue;
            }
            try {
                dis.readByte();  // skip 跳不动就用读顶掉一字节，保证一定在前进
                n--;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private static String readFourCc(DataInputStream dis) throws IOException {
        byte[] b = new byte[4];
        dis.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
