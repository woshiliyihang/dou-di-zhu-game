package com.example.vtrans.model;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 模型解包与校验。
 *
 * <p>模型随 APK 打包在 assets/models 下（未压缩，见 build.gradle 的 noCompress）。
 * 首次启动把缺失的文件从 APK 里拷到 app 私有目录，之后 sherpa-onnx 与
 * CTranslate2 都按普通文件路径加载。全程不联网。
 *
 * <p>注意：asset 必须是「不压缩」存储的。压缩过的 asset 在 open() 时会被整体
 * 解压进内存，622MB 的 model.bin 会直接 OOM，而且拿不到 openFd 的长度。
 */
public class ModelManager {

    private static final String TAG = "ModelManager";
    private static final int BUFFER = 256 * 1024;

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
        // getExternalFilesDir：卸载时自动清理，且 Android 10+ 不需要存储权限。
        // 外部存储不可用时（某些设备/沙盒）退回内部私有目录，避免 NPE 崩溃。
        File ext = ctx.getExternalFilesDir(null);
        File base = ext != null ? ext : ctx.getFilesDir();
        File dir = new File(base, "models");
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

    /** 清单里的全部文件都已存在，才算就绪 */
    public boolean isReady(ModelsManifest.Model m) {
        for (String f : m.files) {
            if (!resolve(f).isFile()) return false;
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

    /**
     * APK 里某个模型文件的字节数；拿不到（asset 被压缩了或文件不存在）返回 -1。
     * 只有未压缩的 asset 才能用 openFd 直接问长度。
     */
    public long assetBytes(String relative) {
        try (AssetFileDescriptor afd =
                     ctx.getAssets().openFd(ModelsManifest.ASSET_ROOT + "/" + relative)) {
            return afd.getLength();
        } catch (IOException e) {
            Log.w(TAG, "读不到 asset 长度（asset 可能被压缩了）: " + relative);
            return -1;
        }
    }

    /** 待解包文件的总字节数，用于给 UI 一个「还要拷多少」的提示 */
    public long bytesToUnpack(List<ModelsManifest.Model> models) {
        long total = 0;
        for (ModelsManifest.Model m : models) {
            for (String f : m.files) {
                if (resolve(f).isFile()) continue;
                long n = assetBytes(f);
                if (n < 0) return -1;
                total += n;
            }
        }
        return total;
    }

    /** 把缺失的文件从 APK 拷到私有目录。抛 IOException 表示失败，可重试。 */
    public void ensure(ModelsManifest.Model m, Progress progress) throws IOException {
        AssetManager am = ctx.getAssets();
        for (String f : m.files) {
            File dst = resolve(f);
            if (dst.isFile()) continue;
            copyAsset(am, ModelsManifest.ASSET_ROOT + "/" + f, dst, progress, m.label);
            if (progress != null && progress.isCancelled()) return;
        }
    }

    /**
     * 流式拷贝单个 asset。先写 .part 再改名，中断后重跑不会留下半个模型文件。
     * 用 openFd 是为了拿到总长度做进度；asset 被压缩时 openFd 会失败，退回 open()。
     */
    private void copyAsset(AssetManager am, String asset, File dst, Progress progress,
                           String stage) throws IOException {
        if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
        File tmp = new File(dst.getAbsolutePath() + ".part");
        long total = -1;
        InputStream in;
        AssetFileDescriptor afd = null;
        try {
            afd = am.openFd(asset);
            total = afd.getLength();
            in = afd.createInputStream();
        } catch (IOException e) {
            Log.w(TAG, "openFd 失败，退回 open()（该 asset 被压缩，会慢一些）: " + asset);
            in = am.open(asset);
        }

        long done = 0;
        long lastReport = 0;
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null && done - lastReport > 8 * 1024 * 1024) {
                        lastReport = done;
                        progress.onProgress(done, total, stage);
                        if (progress.isCancelled()) return;
                    }
                }
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // no-op
            }
            if (afd != null) {
                try {
                    afd.close();
                } catch (IOException ignored) {
                    // no-op
                }
            }
        }
        if (progress != null) progress.onProgress(done, total, stage);
        // openFd 成功时 total 精确：字节数对不上说明读到一半断流（磁盘满/IO 错误），
        // 绝不能把半个文件改名当成品，否则加载时静默失败。删掉 .part 让下次重试。
        if (total >= 0 && done != total) {
            if (!tmp.delete()) Log.w(TAG, "残缺 .part 删除失败: " + tmp);
            throw new IOException("模型解包不完整: " + asset + " " + done + "/" + total);
        }
        if (dst.exists() && !dst.delete()) Log.w(TAG, "旧文件删除失败: " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("无法移动到 " + dst);
    }

    /** 删除某个模型的所有文件（设置页「删除」用；再点一次「解包」就能从 APK 里恢复） */
    public void delete(ModelsManifest.Model m) {
        for (String f : m.files) {
            File file = resolve(f);
            if (file.exists() && !file.delete()) Log.w(TAG, "删除失败: " + file);
        }
    }

    /**
     * APK 里缺哪些必备模型文件（排查打包问题用）。
     * 只看必备项：Whisper 是可选的，没打包是正常的。
     */
    public List<String> missingAssets() {
        List<String> out = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ModelsManifest.Model m : ModelsManifest.required()) {
            names.addAll(Arrays.asList(m.files));
        }
        for (String f : names) {
            try {
                ctx.getAssets().open(ModelsManifest.ASSET_ROOT + "/" + f).close();
            } catch (IOException e) {
                out.add(f);
            }
        }
        return out;
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
        // v 是 double：i==0 走 %d 时必须转成 long，否则 String.format 抛
        // IllegalFormatConversionException（d != java.lang.Double）
        return i == 0
                ? String.format(Locale.getDefault(), "%d %s", (long) v, units[i])
                : String.format(Locale.getDefault(), "%.1f %s", v, units[i]);
    }
}
