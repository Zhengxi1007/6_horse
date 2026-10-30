package com.qss.license;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动门禁：首次打开弹窗绑定，之后每次启动做「本地快速判定 + 后台静默复检」。
 *
 * <p>判定顺序（{@link #evaluate}）：
 * <ol>
 *   <li>本地无授权记录 → {@code NEEDS_ACTIVATION}（弹激活界面）；</li>
 *   <li>系统时钟被回拨 → {@code DENIED}（本地到期判定已不可信，必须联网重判）；</li>
 *   <li>本地到期时刻已过 → {@code DENIED}；</li>
 *   <li>复检间隔未到 → {@code GRANTED}（离线快路径，不耗电不依赖网络）；
 *       间隔已到 → 发心跳；网络失败时按离线宽限期决定是否放行。</li>
 * </ol>
 *
 * <p><b>为什么不能只靠心跳</b>：{@code /cards/use} 对<b>次数卡</b>每次调用都会扣 1 次
 * （{@code CardService#redeemCard} 的 count 分支无条件 {@code remainingCount - 1}）。
 * 因此 {@link #isHeartbeatAllowed} 只在卡类型为 {@code time} / {@code time_count} 时才发心跳，
 * 次数卡改走只读的 {@link #queryBindReadOnly}。这条约束是从后端源码读出来的，
 * 若将来给次数卡也开心跳，用户会看到“次数莫名减少”。
 */
public final class LicenseGate {

    private static final String TAG = "LicenseGate";

    public enum Verdict {
        /** 放行进入主界面。 */
        GRANTED,
        /** 从未成功激活，需要展示激活界面。 */
        NEEDS_ACTIVATION,
        /** 明确不可用（过期/换机/停用/时钟回拨），需要展示激活界面并给出原因。 */
        DENIED,
        /** 网络不可达但仍在离线宽限期内，放行（界面应提示“未联网校验”）。 */
        GRANTED_OFFLINE
    }

    public interface Callback {
        /** 保证在主线程回调。 */
        void onResult(Verdict verdict, KamiResult detail);
    }

    private static volatile KamiConfig config;
    private static volatile KamiClient client;
    private static ExecutorService io;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private LicenseGate() {}

    /** 在 {@code Application#onCreate} 里调用一次。 */
    public static void init(Context app, KamiConfig cfg) {
        config = cfg;
        client = new KamiClient(cfg);
        io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "kami-license-io");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
    }

    private static void requireInit() {
        if (client == null) throw new IllegalStateException("LicenseGate.init() must be called first");
    }

    // ------------------------------------------------------------ 主流程

    /**
     * 异步做启动检查。调用点放在启动页（本例 {@code StartActivity}）的 {@code onCreate}。
     *
     * <p>先给一次本地快判：若本地明确未激活或已过期，立即回调，不等网络（弱网下体验差别很大）。
     */
    public static void checkAsync(final Context ctx, final Callback cb) {
        requireInit();
        final Context app = ctx.getApplicationContext();

        Verdict local = evaluateLocal(app);
        if (local == Verdict.NEEDS_ACTIVATION) {
            post(cb, local, new KamiResult(KamiResult.Status.NOT_ACTIVATED));
            return;
        }
        if (!running.compareAndSet(false, true)) {
            // 已有检查在跑（例如用户快速重启 Activity），直接复用本地结论，避免并发心跳
            post(cb, local == Verdict.DENIED ? local : Verdict.GRANTED,
                    new KamiResult(KamiResult.Status.VALID, "concurrent check in flight"));
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                KamiResult r;
                try {
                    if (local == Verdict.DENIED) {
                        // 本地已判过期，仍向服务端确认一次：管理员加时后应立即恢复可用
                        r = refreshFromServer(app);
                    } else if (shouldHeartbeatNow(app)) {
                        r = heartbeat(app);
                    } else {
                        r = new KamiResult(KamiResult.Status.VALID, "within recheck interval");
                    }
                } catch (Throwable t) {
                    // 任何未预期异常都不能被当成“已授权”，同时必须释放 running
                    Log.e(TAG, "checkAsync failed", t);
                    r = new KamiResult(KamiResult.Status.SERVER_ERROR, String.valueOf(t));
                } finally {
                    running.set(false);
                }
                post(cb, toVerdict(app, r), r);
            }
        });
    }

    /** 首次绑定：用户输入卡密后调用。成功会写入本地状态。 */
    public static void activateAsync(final Context ctx, final String cardKey, final ActivateCallback cb) {
        requireInit();
        final Context app = ctx.getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                KamiResult r;
                try {
                    r = client.activate(app, cardKey);
                } catch (Throwable t) {
                    Log.e(TAG, "activate failed", t);
                    r = new KamiResult(KamiResult.Status.SERVER_ERROR, String.valueOf(t));
                }
                if (r.isGranted()) {
                    String mc = MachineCode.get(app);
                    long exp = resolveExpiry(app, cardKey.trim(), mc, r);
                    SecureLicenseStore.saveAuthorized(app, cardKey.trim(), mc, exp);
                    saveCardType(app, queryCardType(app, cardKey.trim()));
                } else if (r.status == KamiResult.Status.EXPIRED
                        || r.status == KamiResult.Status.BOUND_ELSEWHERE) {
                    // 这两类说明本地状态与服务端冲突，清掉以免下次启动走快路径误放行
                    SecureLicenseStore.clear(app);
                }
                postActivate(cb, r);
            }
        });
    }

    public interface ActivateCallback {
        void onResult(KamiResult result);
    }

    // ------------------------------------------------------------ 判定

    /** 本地判定，不联网。 */
    public static Verdict evaluateLocal(Context app) {
        requireInit();
        if (!SecureLicenseStore.isLocallyActivated(app)) return Verdict.NEEDS_ACTIVATION;
        if (SecureLicenseStore.isClockRolledBack(app)) {
            Log.w(TAG, "clock rolled back detected");
            return Verdict.DENIED;
        }
        long exp = SecureLicenseStore.expiryMillis(app);
        if (exp > 0 && System.currentTimeMillis() > exp) return Verdict.DENIED;
        return Verdict.GRANTED;
    }

    /** 联网复检（同步，后台线程调用）。 */
    public static KamiResult heartbeat(Context app) {
        requireInit();
        KamiResult r = client.heartbeat(app);
        if (r.isGranted()) {
            SecureLicenseStore.touchConfirmed(app);
            return r;
        }
        if (r.status.isHardDeny()) {
            SecureLicenseStore.clear(app);
            return r;
        }
        // 网络/服务异常：离线宽限期
        long grace = config.offlineGraceMillis;
        long confirmed = SecureLicenseStore.lastConfirmedAt(app);
        if (grace > 0 && confirmed > 0
                && System.currentTimeMillis() - confirmed <= grace
                && !SecureLicenseStore.isClockRolledBack(app)) {
            Log.i(TAG, "heartbeat failed but inside offline grace: " + r);
            return new KamiResult(KamiResult.Status.VALID, "offline-grace:" + r.rawMessage);
        }
        return r;
    }

    /** 本地判过期时向服务端再确认一次（管理员后台加时后能立即恢复）。 */
    private static KamiResult refreshFromServer(Context app) {
        String card = SecureLicenseStore.getCardKey(app);
        if (card == null) return new KamiResult(KamiResult.Status.NOT_ACTIVATED);
        if (!isHeartbeatAllowed(app)) {
            // 次数卡不能用 /cards/use 复检（会扣次），改用只读查询看绑定是否被管理员清理
            KamiResult q = client.queryBind(card);
            return q.bound ? new KamiResult(KamiResult.Status.VALID, "bind-query ok") : q;
        }
        KamiResult r = client.heartbeat(app);
        if (r.isGranted()) {
            long exp = resolveExpiry(app, card, MachineCode.get(app), r);
            SecureLicenseStore.saveAuthorized(app, card, MachineCode.get(app), exp);
        } else if (r.status.isHardDeny()) {
            SecureLicenseStore.clear(app);
        }
        return r;
    }

    private static Verdict toVerdict(Context app, KamiResult r) {
        if (r.isGranted()) {
            // 复检成功后再走一次本地判定：宽限期放行时可能仍处在“已过期但没联网确认”的窗口
            return SecureLicenseStore.expiryMillis(app) > 0
                    && System.currentTimeMillis() > SecureLicenseStore.expiryMillis(app)
                    ? Verdict.DENIED : Verdict.GRANTED;
        }
        if (r.status == KamiResult.Status.NOT_ACTIVATED) return Verdict.NEEDS_ACTIVATION;
        if (r.status == KamiResult.Status.NETWORK_ERROR
                || r.status == KamiResult.Status.SERVER_ERROR) {
            Verdict local = evaluateLocal(app);
            return local == Verdict.GRANTED ? Verdict.GRANTED_OFFLINE : local;
        }
        return Verdict.DENIED;
    }

    /**
     * 距上次成功确认是否已到复检间隔。
     * 间隔未到直接判 GRANTED —— 这保证飞行模式/弱网启动不会白等超时。
     */
    private static boolean shouldHeartbeatNow(Context app) {
        if (!isHeartbeatAllowed(app)) return false;
        long interval = config.heartbeatIntervalMillis;
        if (interval <= 0) return true;
        long last = SecureLicenseStore.lastConfirmedAt(app);
        return last <= 0 || System.currentTimeMillis() - last >= interval;
    }

    /**
     * 只有时间类卡才允许用 /cards/use 做心跳。
     *
     * <p>次数卡走该端点<b>每次都会真扣 1 次</b>（{@code CardService#redeemCard} 的 count 分支
     * 无条件 {@code remainingCount - 1}），所以绝不能拿次数卡心跳。
     * 类型未知时（旧版本遗留状态 / 激活后查询类型失败）<b>不发心跳</b>，
     * 改由只读的 queryBind 确认绑定关系并顺便把类型补上；仍判不出就维持本地状态，
     * 等下次激活/心跳窗口再确认。误扣一次的代价高于晚一次复检。
     */
    private static boolean isHeartbeatAllowed(Context app) {
        String t = loadCardType(app);
        if (t != null) return "time".equals(t) || "time_count".equals(t);
        // 类型未知：先做一次无副作用的只读查询补齐
        String card = SecureLicenseStore.getCardKey(app);
        if (card == null) return false;
        try {
            String probed = client.queryBind(card).cardType;
            if (probed != null) {
                saveCardType(app, probed);
                return "time".equals(probed) || "time_count".equals(probed);
            }
        } catch (Exception e) {
            Log.w(TAG, "card type probe failed: " + e);
        }
        return false;
    }

    // ------------------------------------------------------------ 卡类型缓存

    private static final String SLOT_TYPE_PLAIN = "card_type";
    private static final String TYPE_PREF_FILE = "qss_lic_meta";

    private static void saveCardType(Context app, String type) {
        if (type == null) return;
        app.getSharedPreferences(TYPE_PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(SLOT_TYPE_PLAIN, type).apply();
    }

    private static String loadCardType(Context app) {
        return app.getSharedPreferences(TYPE_PREF_FILE, Context.MODE_PRIVATE)
                .getString(SLOT_TYPE_PLAIN, null);
    }

    /**
     * 从只读绑定查询里取 cardType。失败返回 null（不影响激活成功本身）。
     * 用只读接口而非解析心跳响应，是因为 {@code /cards/use} 的成功响应体不带卡类型。
     */
    private static String queryCardType(Context app, String cardKey) {
        try {
            return client.queryBind(cardKey).cardType;
        } catch (Exception e) {
            Log.w(TAG, "queryCardType failed: " + e);
            return null;
        }
    }

    /**
     * 推算本地到期时刻。
     *
     * <p>当前 {@code /cards/use} 的成功响应<b>不含 expire_time</b>，也没有别的已鉴权端点会返回它，
     * 所以只能返回 0（= 不按本地时间判过期，完全依赖心跳）。
     * 一旦后端按 README-BACKEND-PATCH.md 在公开查询响应里补上 expire_time，
     * 这里即可换成读服务端权威值，界面也就能显示真实剩余天数。
     */
    private static long resolveExpiry(Context app, String cardKey, String machineCode, KamiResult ok) {
        // 优先用服务端权威剩余秒数（需后端按 README-BACKEND-PATCH.md 补 remaining_seconds）。
        // 拿不到就返回 0 = 不按本地时间判过期，完全依赖心跳，避免误锁正常用户。
        try {
            KamiResult q = client.queryBind(cardKey);
            if (q.remainingSeconds != null && q.remainingSeconds > 0) {
                return System.currentTimeMillis() + q.remainingSeconds * 1000L;
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveExpiry via queryBind failed: " + e);
        }
        return 0L;
    }

    /** 供界面显示的剩余天数；无法得知时返回 {@link #DAYS_UNKNOWN}。 */
    public static final int DAYS_UNKNOWN = -1;

    public static int remainingDays(Context app) {
        long exp = SecureLicenseStore.expiryMillis(app);
        if (exp <= 0) return DAYS_UNKNOWN;
        long diff = exp - System.currentTimeMillis();
        if (diff <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, diff / (24L * 3600 * 1000) + 1);
    }

    // ------------------------------------------------------------ 线程切换

    private static void post(final Callback cb, final Verdict v, final KamiResult r) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(v, r);
            }
        });
    }

    private static void postActivate(final ActivateCallback cb, final KamiResult r) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r);
            }
        });
    }
}
