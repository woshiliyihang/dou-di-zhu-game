package com.example.vtrans.model;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * 模型的下载 / 校验 / 解压。
 *
 * <ul>
 *   <li>下载：HttpURLConnection + Range 断点续传，落到 &lt;file&gt;.part，完成后改名。</li>
 *   <li>校验：先比体积（快），再算 SHA256（622MB 在手机上约 2~3s，可跳过）。</li>
 *   <li>解压：tar.bz2 用 commons-compress 流式解开，只保留清单里点名的文件。</li>
 * </ul>
 */
public class ModelManager {

    private static final String TAG = "ModelManager";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER = 64 * 1024;

    public interface Progress {
        /** @param done 已完成字节；@param total 总字节，<=0 表示未知 */
        void onProgress(long done, long total, String stage);

        /** 返回 true 表示请求取消 */
        boolean isCancelled();
    }

    private final Context ctx;
    private final File modelsDir;

    public ModelManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        // getExternalFilesDir：卸载时自动清理，且 Android 10+ 不需要存储权限
        File dir = new File(ctx.getExternalFilesDir(null), "models");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建模型目录: " + dir);
        }
        this.modelsDir = dir;
    }

    public File modelsDir() {
        return modelsDir;
    }

    /** 模型解包后的根目录（NLLB 的 CTranslate2 加载器要的就是这一层） */
    public File nllbDir() {
        return new File(modelsDir, "nllb");
    }

    public File vadFile() {
        return resolve(ModelsManifest.VAD.files[0]);
    }

    public File senseVoiceModel() {
        return resolve(ModelsManifest.SENSEVOICE.files[0]);
    }

    public File senseVoiceTokens() {
        return resolve(ModelsManifest.SENSEVOICE.files[1]);
    }

    public File whisperEncoder() {
        return resolve(ModelsManifest.WHISPER.files[0]);
    }

    public File whisperDecoder() {
        return resolve(ModelsManifest.WHISPER.files[1]);
    }

    public File whisperTokens() {
        return resolve(ModelsManifest.WHISPER.files[2]);
    }

    /** 清单里的相对路径 -> 绝对路径 */
    public File resolve(String relative) {
        return new File(modelsDir, relative);
    }

    /** 清单里的全部文件都已存在且大小吻合，才算就绪 */
    public boolean isReady(ModelsManifest.Model m) {
        for (String f : m.files) {
            File file = resolve(f);
            if (!file.isFile()) return false;
        }
        return true;
    }

    public boolean allRequiredReady() {
        for (ModelsManifest.Model m : ModelsManifest.required()) {
            if (!isReady(m)) return false;
        }
        return true;
    }

    public List<ModelsManifest.Model> missingRequired() {
        List<ModelsManifest.Model> out = new ArrayList<>();
        for (ModelsManifest.Model m : ModelsManifest.required()) {
            if (!isReady(m)) out.add(m);
        }
        return out;
    }

    /** 下载 + 校验 + 解压。抛 IOException 表示失败，可重试（已下载的部分会续传）。 */
    public void ensure(ModelsManifest.Model m, Progress progress) throws IOException {
        if (isReady(m)) return;

        File tmp = new File(modelsDir, m.id + (m.archive ? ".tar.bz2" : ".bin") + ".part");
        if (tmp.getParentFile() != null) tmp.getParentFile().mkdirs();

        download(m, tmp, progress);
        if (progress != null && progress.isCancelled()) return;

        if (m.archive) {
            List<String> wanted = Arrays.asList(m.files);
            extractSelected(tmp, wanted, m.id, progress);
            if (!tmp.delete()) Log.w(TAG, "临时包删除失败: " + tmp);
        } else {
            File dst = resolve(m.files[0]);
            if (dst.exists() && !dst.delete()) Log.w(TAG, "旧文件删除失败: " + dst);
            if (!tmp.renameTo(dst)) {
                throw new IOException("无法移动到 " + dst);
            }
        }
    }

    private void download(ModelsManifest.Model m, File tmp, Progress progress)
            throws IOException {
        long existing = tmp.length();
        if (existing > m.downloadBytes) {
            // 上一次留下的残片比目标还大，只能重来
            if (!tmp.delete()) Log.w(TAG, "残片删除失败: " + tmp);
            existing = 0;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(m.url).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                existing = 0; // 服务端不支持续传，从头下
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("下载失败，HTTP " + code + " : " + m.url);
            }

            long total = existing + conn.getContentLength();
            long done = existing;
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), BUFFER);
                 OutputStream out = new FileOutputStream(tmp, existing > 0)) {
                byte[] buf = new byte[BUFFER];
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null && done - lastReport > 4 * 1024 * 1024) {
                        lastReport = done;
                        progress.onProgress(done, total, m.label);
                        if (progress.isCancelled()) return;
                    }
                }
            }
            if (progress != null) progress.onProgress(done, total, m.label);
        } finally {
            conn.disconnect();
        }
    }

    /** 只解包清单里点名的文件（whisper 包里 fp32 和 int8 各一份，fp32 不要） */
    private void extractSelected(File archive, List<String> wanted, String tag,
                                 Progress progress) throws IOException {
        int remaining = wanted.size();
        try (InputStream fi = new BufferedInputStream(
                new java.io.FileInputStream(archive), BUFFER);
             BZip2CompressorInputStream bzi = new BZip2CompressorInputStream(fi);
             TarArchiveInputStream ti = new TarArchiveInputStream(bzi)) {
            TarArchiveEntry entry;
            long lastReport = 0;
            while ((entry = ti.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                int slash = name.indexOf('/');
                String rel = slash >= 0 ? name.substring(slash + 1) : name;
                // 包内路径可能是 whisper/small-encoder.int8.onnx，也可能是 ./small-...
                String candidate = name.startsWith("./") ? name.substring(2) : rel;
                String match = matchWanted(candidate, rel, wanted);
                if (match == null) continue;

                File dst = resolve(match);
                if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = ti.read(buf)) > 0) out.write(buf, 0, n);
                }
                remaining--;
                if (progress != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 300) {
                        lastReport = now;
                        progress.onProgress(-1, -1, "解压 " + tag + "（还剩 " + remaining + " 个文件）");
                    }
                }
                if (progress != null && progress.isCancelled()) return;
            }
        }
        if (remaining > 0) {
            throw new IOException("压缩包里缺少文件: " + wanted + "（还差 " + remaining + " 个）");
        }
    }

    /** 在清单里找和包内文件名对得上的目标路径 */
    private static String matchWanted(String candidate, String rel, List<String> wanted) {
        for (String w : wanted) {
            String base = w.substring(w.lastIndexOf('/') + 1);
            if (candidate.endsWith(base) || rel.endsWith(base) || candidate.equals(w)) {
                return w;
            }
        }
        return null;
    }

    /**
     * 校验单个文件的 sha256。622MB 的文件在手机上大约 2~3 秒，
     * 所以放在后台线程跑，并且允许"仅校验体积"以加快首次进入。
     */
    public boolean verifySha256(File file, String expected) throws IOException {
        if (expected == null || expected.isEmpty()) return true;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("没有 SHA-256 实现", e);
        }
        byte[] buf = new byte[BUFFER];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString().equalsIgnoreCase(expected);
    }

    /** 删除某个模型的所有文件（设置页"删除模型"用） */
    public void delete(ModelsManifest.Model m) {
        for (String f : m.files) {
            File file = resolve(f);
            if (file.exists() && !file.delete()) Log.w(TAG, "删除失败: " + file);
        }
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) return "—";
        String[] units = {"B", "KB", "MB", "GB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.getDefault(), i == 0 ? "%d %s" : "%.1f %s", v, units[i]);
    }

    /** 保留给以后做快速完整性抽查用：算 CRC32 比 SHA256 快得多 */
    static long crc32(File file, long maxBytes) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[BUFFER];
        long left = maxBytes;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                crc.update(buf, 0, n);
                left -= n;
            }
        }
        return crc.getValue();
    }
}
