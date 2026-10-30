package com.qss.license;

import android.annotation.SuppressLint;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 【环节 ①】生成机器码。
 *
 * <p>设计目标：
 * <ul>
 *   <li><b>稳定</b>：卸载重装、清除应用数据、恢复出厂以外的常规操作都不变 —— 否则用户重装后会被误判“已绑定其他设备”。</li>
 *   <li><b>短</b>：19 字符 {@code XXXX-XXXX-XXXX-XXXX}，远小于服务端 machine_code 列宽 255、device_id 列宽 64。</li>
 *   <li><b>可核对</b>：分 4 段带连字符，便于用户截图发给管理员。</li>
 * </ul>
 *
 * <p>取值优先级（多源回退，任何单点失败不影响结果）：
 * <ol>
 *   <li>{@code Settings.Secure.ANDROID_ID} —— 出厂写入，按签名派生，卸载重装不变；</li>
 *   <li>{@code Build} 硬件属性串 —— 只能区分设备型号，作为掺入材料提高区分度；</li>
 *   <li>自建持久 ID —— 前两者都取不到时生成随机 ID，并落盘到应用私有目录<b>之外</b>的
 *       {@code getNoBackupFilesDir()} 与 {@code EXTERNAL_FILES_DIR} 双份镜像，
 *       尽量避免清数据即丢失。<b>注意：用户手动卸载仍可能删除外部目录，届时机器码会变，需要走后台解绑。</b></li>
 * </ol>
 *
 * <p><b>安全边界（必须知道）</b>：机器码不是机密。后台
 * {@code POST /api/public/cards/machine-bind/query} 目前<b>无鉴权</b>且会回吐明文机器码，
 * 拿到卡密串的人即可读出/伪造绑定关系。因此本模块的“一机一码”只是<b>防普通用户多设备共用</b>的
 * 便利约束，不是访问控制。真正的授权判定必须由服务端权威完成（见 README-BACKEND-PATCH.md）。
 */
public final class MachineCode {

    /** 参与摘要的固定盐，避免不同 App 用同一 ANDROID_ID 得到同值，也让机器码不可跨应用碰撞反查。 */
    private static final String SALT = "qss-kami-machine-v1";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String NOBACKUP_FILE = "mc.bin";
    private static volatile String cached;

    private MachineCode() {}

    /** 取得本机机器码，形如 {@code A1B2-C3D4-E5F6-0718}。全程无网络，可在主线程调用。 */
    public static String get(Context context) {
        String v = cached;
        if (v != null) return v;
        synchronized (MachineCode.class) {
            if (cached == null) cached = compute(context.getApplicationContext());
            return cached;
        }
    }

    private static String compute(Context app) {
        StringBuilder raw = new StringBuilder(SALT);

        String androidId = readAndroidId(app);
        if (!TextUtils.isEmpty(androidId)) raw.append('|').append(androidId);

        raw.append('|').append(hardwareSeed());

        if (TextUtils.isEmpty(androidId)) {
            // ANDROID_ID 缺失（部分定制 ROM / 多用户工作资料返回 null）→ 用自建持久 ID 兜底
            String persisted = persistedId(app, raw.toString());
            if (!TextUtils.isEmpty(persisted)) raw.append("|p=").append(persisted);
        }

        return format(sha256Hex(raw.toString()).substring(0, 16));
    }

    @SuppressLint("HardwareIds")
    private static String readAndroidId(Context app) {
        try {
            return Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Build 属性串。不含序列号（Android 10+ 需特权），仅用于增加区分度与抗碰撞。 */
    private static String hardwareSeed() {
        return join(android.os.Build.BRAND, android.os.Build.MODEL,
                android.os.Build.DEVICE, android.os.Build.MANUFACTURER,
                android.os.Build.HARDWARE);
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!TextUtils.isEmpty(p)) {
                if (sb.length() > 0) sb.append('~');
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /**
     * 自建持久 ID：优先 noBackupFilesDir（不参与云备份），并在外部私有目录镜像一份，
     * 读取时任一命中即复用，两处都缺失才新生成。
     */
    private static String persistedId(Context app, String seed) {
        String fromDisk = readPersisted(app);
        if (!TextUtils.isEmpty(fromDisk)) return fromDisk;

        String fresh;
        try {
            byte[] rnd = new byte[16];
            new SecureRandom().nextBytes(rnd);
            fresh = new BigInteger(1, rnd).toString(16);
            while (fresh.length() < 32) fresh = "0" + fresh;
        } catch (Throwable t) {
            fresh = sha256Hex(seed + System.nanoTime()).substring(0, 32);
        }
        writePersisted(app, fresh);
        return fresh;
    }

    private static String readPersisted(Context app) {
        String v = readNoBackup(app);
        if (TextUtils.isEmpty(v)) v = readExternal(app);
        if (!TextUtils.isEmpty(v)) {
            // 互补：只在一处存在时补齐另一处
            if (TextUtils.isEmpty(readNoBackup(app))) writeTo(new File(noBackupFile(app)), v);
            if (TextUtils.isEmpty(readExternal(app))) writeTo(new File(externalFile(app)), v);
        }
        return v;
    }

    private static void writePersisted(Context app, String v) {
        writeTo(new File(noBackupFile(app)), v);
        writeTo(new File(externalFile(app)), v);
    }

    private static String readNoBackup(Context app) {
        try {
            File f = new File(app.getNoBackupFilesDir(), NOBACKUP_FILE);
            return f.isFile() ? read(f) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readExternal(Context app) {
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) return null;
            File f = new File(dir, NOBACKUP_FILE);
            return f.isFile() ? read(f) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String noBackupFile(Context app) {
        return new File(app.getNoBackupFilesDir(), NOBACKUP_FILE).getAbsolutePath();
    }

    private static String externalFile(Context app) {
        File dir = app.getExternalFilesDir(null);
        return new File(dir == null ? app.getFilesDir() : dir, NOBACKUP_FILE).getAbsolutePath();
    }

    private static void writeTo(File target, String value) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try {
            DataOutputStream out = new DataOutputStream(new FileOutputStream(target));
            try {
                out.writeUTF(value);
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // 落盘失败不影响本次运行（内存已缓存），下次启动若仍缺失则机器码可能变化 → 需后台解绑
        }
    }

    private static String read(File f) throws IOException {
        DataInputStream in = new DataInputStream(new FileInputStream(f));
        try {
            return in.readUTF();
        } finally {
            in.close();
        }
    }

    /** XXXXXXXX-XXXXXXXX（16 位 hex 分两段）→ 对外展示为 XXXX-XXXX-XXXX-XXXX。 */
    private static String format(String hex16) {
        StringBuilder sb = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(Character.toUpperCase(hex16.charAt(i)));
        }
        return sb.toString();
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(UTF8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() < 2) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
