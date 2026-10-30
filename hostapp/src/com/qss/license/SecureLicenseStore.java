package com.qss.license;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.nio.charset.Charset;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 【环节 ④】本地状态存储。
 *
 * <p>存的东西：卡密、机器码、服务端确认时刻、本地推算到期时间、最大观测墙钟。
 *
 * <p>为什么不是明文 SharedPreferences：明文 XML 在 root/备份/`adb pull` 下可直接改
 * {@code expiry_time} 到 2099 年。这里用 <b>AndroidKeyStore 里的 AES-256-GCM 密钥</b>加密，
 * 密钥不可导出（hardware-backed 时连 root 也拿不到）。GCM 自带 128bit 认证标签，
 * <b>任何一位改动都会解密失败</b> —— 认证标签即完整性签名，无需另挂 HMAC。
 *
 * <p>解密失败（{@link javax.crypto.AEADBadTagException}）一律视为“无有效状态”，
 * 直接返回未激活，落回激活界面。
 *
 * <p>能防的：手改/替换密文、跨设备拷贝密文（明文里含机器码，会校验不一致）。
 * 防不了的：整机镜像回滚到更早快照 —— 这属于客户端存储的固有上限，
 * 唯一真解是服务端权威（见 README-BACKEND-PATCH.md）。因此本类额外记录
 * {@code maxSeenWallClock} 用于检出“把系统时间往回调”这类最常见的续命手法。
 */
public final class SecureLicenseStore {

    private static final String TAG = "SecureLicenseStore";
    private static final String PREF_FILE = "qss_lic_v1";
    private static final String KEY_ALIAS = "qss_kami_license_aes";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    // 密文条目名（值本身是 base64(iv||ciphertext+tag)）
    private static final String SLOT_CARD = "k0";
    private static final String SLOT_MACHINE = "k1";
    private static final String SLOT_CONFIRMED = "k2";
    private static final String SLOT_EXPIRY = "k3";
    private static final String SLOT_SERVER_STATE = "k4";
    private static final String SLOT_MAX_WALL = "k5";

    private static volatile SharedPreferences prefs;

    private SecureLicenseStore() {}

    // ------------------------------------------------------------- 写入接口

    /**
     * 记录一次成功的服务端授权。
     *
     * @param cardKey        用户输入并核销成功的卡密
     * @param machineCode     本机机器码（同时存入密文，读回时校验，防跨机拷贝）
     * @param expiryMillis    本地推算的到期时刻；{@code <= 0} 表示未知（后台不回 expire_time），
     *                        此时完全依赖心跳判活
     */
    public static void saveAuthorized(Context ctx, String cardKey, String machineCode,
                                      long expiryMillis) {
        long now = System.currentTimeMillis();
        put(ctx, SLOT_CARD, cardKey);
        put(ctx, SLOT_MACHINE, machineCode);
        put(ctx, SLOT_CONFIRMED, String.valueOf(now));
        put(ctx, SLOT_EXPIRY, String.valueOf(expiryMillis));
        put(ctx, SLOT_SERVER_STATE, "1");
        bumpMaxWallClock(ctx, now);
    }

    /** 心跳成功后刷新“服务端确认时刻”，让离线宽限期重新计时。 */
    public static void touchConfirmed(Context ctx) {
        long now = System.currentTimeMillis();
        put(ctx, SLOT_CONFIRMED, String.valueOf(now));
        put(ctx, SLOT_SERVER_STATE, "1");
        bumpMaxWallClock(ctx, now);
    }

    /** 服务端硬否定（过期/换机/停用）后清除授权，强制回到激活界面。 */
    public static void clear(Context ctx) {
        SharedPreferences sp = sp(ctx);
        SharedPreferences.Editor e = sp.edit();
        e.remove(SLOT_CARD).remove(SLOT_MACHINE).remove(SLOT_CONFIRMED)
                .remove(SLOT_EXPIRY).remove(SLOT_SERVER_STATE);
        e.apply();
    }

    // ------------------------------------------------------------- 读取接口

    public static String getCardKey(Context ctx) {
        return get(ctx, SLOT_CARD);
    }

    public static boolean hasCardKey(Context ctx) {
        String k = getCardKey(ctx);
        return k != null && k.length() > 0;
    }

    /** 已存储的机器码；与本机不一致时返回 null（视为非法状态）。 */
    public static String getBoundMachineCode(Context ctx) {
        String stored = get(ctx, SLOT_MACHINE);
        if (stored == null) return null;
        return stored.equals(MachineCode.get(ctx)) ? stored : null;
    }

    /** 上次与服务端成功确认的时刻（毫秒）；从未确认返回 0。 */
    public static long lastConfirmedAt(Context ctx) {
        return parseLong(get(ctx, SLOT_CONFIRMED));
    }

    /** 本地推算到期时刻；未知或永久返回 0（0 表示“不按本地时间判过期”）。 */
    public static long expiryMillis(Context ctx) {
        return parseLong(get(ctx, SLOT_EXPIRY));
    }

    /** 是否曾成功绑定过（密文存在且机器码匹配）。 */
    public static boolean isLocallyActivated(Context ctx) {
        return hasCardKey(ctx) && getBoundMachineCode(ctx) != null
                && lastConfirmedAt(ctx) > 0;
    }

    /**
     * 系统时钟是否被往回调过（超过 10 分钟容差）。
     * 常见续命手法：到期后把手机时间改到过去，让本地 expire_time 看起来还没到。
     */
    public static boolean isClockRolledBack(Context ctx) {
        long maxSeen = parseLong(get(ctx, SLOT_MAX_WALL));
        if (maxSeen <= 0) return false;
        return System.currentTimeMillis() < (maxSeen - CLOCK_ROLLBACK_TOLERANCE_MS);
    }

    private static final long CLOCK_ROLLBACK_TOLERANCE_MS = 10L * 60 * 1000;

    private static void bumpMaxWallClock(Context ctx, long now) {
        long prev = parseLong(get(ctx, SLOT_MAX_WALL));
        if (now > prev) put(ctx, SLOT_MAX_WALL, String.valueOf(now));
    }

    // ------------------------------------------------------------- crypto

    private static SharedPreferences sp(Context ctx) {
        SharedPreferences p = prefs;
        if (p == null) {
            synchronized (SecureLicenseStore.class) {
                p = prefs;
                if (p == null) {
                    p = ctx.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
                    prefs = p;
                }
            }
        }
        return p;
    }

    private static void put(Context ctx, String slot, String plaintext) {
        try {
            sp(ctx).edit().putString(slot, encrypt(plaintext)).apply();
        } catch (Exception e) {
            Log.e(TAG, "put failed slot=" + slot, e);
        }
    }

    private static String get(Context ctx, String slot) {
        String blob = sp(ctx).getString(slot, null);
        if (blob == null || blob.length() == 0) return null;
        try {
            return decrypt(blob);
        } catch (Exception e) {
            // 篡改 / 密钥失效（恢复出厂、重装后 keyguard 密钥被清）→ 当作无状态
            Log.w(TAG, "decrypt failed slot=" + slot + " -> " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey());
        byte[] iv = cipher.getIV();          // GCM 每次 12 字节随机 IV
        byte[] ct = cipher.doFinal(plaintext.getBytes(UTF8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String decrypt(String blob) throws Exception {
        byte[] raw = Base64.decode(blob, Base64.NO_WRAP);
        if (raw.length <= 12) throw new IllegalArgumentException("blob too short");
        byte[] iv = new byte[12];
        System.arraycopy(raw, 0, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(raw, 12, raw.length - 12), UTF8);
    }

    /**
     * 取得（或首次生成）AndroidKeyStore 内的 AES-256-GCM 密钥。
     *
     * <p>API 31 才有 {@code setRollbackResistant}：让密钥在快照回滚后不可再用，
     * 可挡“把整机镜像恢复到激活前的旧快照”这一手。这里用反射按能力启用，
     * 因此可用 API 30 及以下的 SDK 编译，并在 31+ 设备上自动生效（见 applyRollbackResistant）。
     */
    private static SecretKey aesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(newKeySpec());
        return kg.generateKey();
    }

    /**
     * setRollbackResistant 是 API 31 新增方法。为了让本模块能用 Android 11 及更旧的 compileSdk
     * 编译（也为了在不支持该特性的设备上不炸），这里用反射按能力启用；失败即静默降级。
     */
    private static void applyRollbackResistant(KeyGenParameterSpec.Builder b) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            b.getClass().getMethod("setRollbackResistant", boolean.class)
                    .invoke(b, Boolean.TRUE);
        } catch (Throwable t) {
            Log.i(TAG, "rollback resistance unavailable: " + t);
        }
    }

    private static KeyGenParameterSpec newKeySpec() {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不绑生物识别/屏锁：否则用户改解锁方式会导致授权全丢，被迫重新激活
                .setUserAuthenticationRequired(false);
        applyRollbackResistant(b);
        return b.build();
    }

    private static long parseLong(String s) {
        if (s == null) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 调试用：确认存储文件位置，便于自测时手动破坏以验证防篡改。 */
    public static File debugDbPath(Context ctx) {
        return new File(new File(ctx.getApplicationInfo().dataDir, "shared_prefs"), PREF_FILE + ".xml");
    }
}
