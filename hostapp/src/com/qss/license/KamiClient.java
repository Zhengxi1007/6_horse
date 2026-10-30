package com.qss.license;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 【环节 ③】请求卡密后台 API。
 *
 * <p>三个动作共用同一批端点：
 * <ul>
 *   <li>{@link #activate} —— 首次输入卡密：核销 + 绑定机器码（{@code POST /api/cards/use}）；</li>
 *   <li>{@link #heartbeat} —— 启动静默复检：同一端点，同机重复提交；</li>
 *   <li>{@link #queryBind} —— 只读查询绑定关系（{@code POST /api/public/cards/machine-bind/query}），不写状态。</li>
 * </ul>
 *
 * <p>硬约束：<b>只用 POST + JSON body</b>。高级加密卡 card_key 含 {@code +}/{@code /}，
 * 走 GET query 会被 URL 解码成空格（服务端虽有 replace(" ","+") 兜底，但依赖它是脆弱的）。
 *
 * <p>所有方法都是阻塞的，必须在后台线程调用（{@link LicenseGate} 已负责线程调度）。
 */
public final class KamiClient {

    private static final String TAG = "KamiClient";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final KamiConfig config;
    private final SSLSocketFactory pinnedFactory;

    public KamiClient(KamiConfig config) {
        this.config = config;
        this.pinnedFactory =TextUtils.isEmpty(config.pinnedCertSha256)
                ? null : buildPinnedFactory(config.pinnedCertSha256);
    }

    /**
     * 首次激活：核销卡密并把本机机器码绑定上去。
     *
     * @param cardKey 用户输入（自动 trim + 转大写由后台负责，这里只 trim）
     */
    public KamiResult activate(Context ctx, String cardKey) {
        return useCard(cardKey, MachineCode.get(ctx), true);
    }

    /**
     * 启动静默复检。服务端行为与 activate 完全一致（同一端点、同一参数），
     * 区别只在于调用时机与本地状态处置，由 {@link LicenseGate} 负责。
     */
    public KamiResult heartbeat(Context ctx) {
        String cardKey = SecureLicenseStore.getCardKey(ctx);
        if (TextUtils.isEmpty(cardKey)) return new KamiResult(KamiResult.Status.NOT_ACTIVATED);
        return useCard(cardKey, MachineCode.get(ctx), false);
    }

    /**
     * 只读查询：这台卡当前绑的是哪个机器码 / 是否永久。不产生任何服务端写入。
     *
     * <p>注意：该端点<b>无鉴权</b>且会回吐明文 machineCode。客户端只在“用户自助解绑”页用一次，
     * 不要拿它做授权判定——它是可被第三方伪造/探测的公开面。
     */
    public KamiResult queryBind(String cardKey) {
        JSONObject body = new JSONObject();
        try {
            body.put("card_key", cardKey);
        } catch (Exception e) {
            return new KamiResult(KamiResult.Status.SERVER_ERROR, "build body failed");
        }
        Response r = post(config.bindQueryUrl(), body.toString());
        if (r.ioError) return new KamiResult(KamiResult.Status.NETWORK_ERROR, r.error);
        if (r.json == null) return new KamiResult(KamiResult.Status.SERVER_ERROR, r.body);
        try {
            JSONObject o = r.json;
            if (!o.optBoolean("success", false)) {
                return KamiResult.fromMessage(o.optString("message"));
            }
            JSONObject d = o.optJSONObject("data");
            String mc = d == null ? null : d.optString("machineCode", null);
            String ct = d == null ? null : d.optString("cardType", null);
            boolean bound = d != null && d.optBoolean("bound", false);
            // remaining_seconds 是后端补丁新增字段；未打补丁时为 null，客户端自动退回“仅靠心跳判活”
            Long remaining = null;
            if (d != null) {
                // 后端补丁字段为 camelCase（与本响应 map 既有风格一致）；
                // 兼容读取 snake_case，避免后端改名时静默退化成“无到期时间”。
                String key = d.has("remainingSeconds") ? "remainingSeconds"
                        : (d.has("remaining_seconds") ? "remaining_seconds" : null);
                if (key != null && !d.isNull(key)) {
                    long v = d.optLong(key, -1L);
                    if (v >= 0) remaining = Long.valueOf(v);
                }
            }
            return new KamiResult(bound ? KamiResult.Status.OK : KamiResult.Status.NOT_FOUND,
                    null, mc, ct, bound, remaining);
        } catch (Exception e) {
            return new KamiResult(KamiResult.Status.SERVER_ERROR, "parse: " + e);
        }
    }

    // ------------------------------------------------------------------ core

    private KamiResult useCard(String cardKey, String machineCode, boolean firstTime) {
        if (TextUtils.isEmpty(cardKey)) {
            return new KamiResult(KamiResult.Status.BAD_FORMAT, "empty card key");
        }
        String trimmed = cardKey.trim();
        JSONObject body = new JSONObject();
        try {
            body.put("card_key", trimmed);
            body.put("machine_code", machineCode);
            // device_id 列宽 64；机器码 19 字符，直接复用，便于后台同一行两处对照
            body.put("device_id", machineCode);
            if (!TextUtils.isEmpty(config.apiKey)) body.put("api_key", config.apiKey);
        } catch (Exception e) {
            return new KamiResult(KamiResult.Status.SERVER_ERROR, "build body failed");
        }

        Response r = post(config.useCardUrl(), body.toString());

        if (r.ioError) {
            Log.w(TAG, (firstTime ? "activate" : "heartbeat") + " network fail: " + r.error);
            return new KamiResult(KamiResult.Status.NETWORK_ERROR, r.error);
        }
        if (r.json == null) {
            return new KamiResult(KamiResult.Status.SERVER_ERROR, "HTTP " + r.code + " non-json");
        }

        JSONObject o = r.json;
        boolean success = o.optBoolean("success", r.code == 200);
        String message = o.optString("message", "");
        if (success) {
            return new KamiResult(KamiResult.Status.OK, message);
        }
        // 400 也要看文案：VALID（复检通过）与各种硬否定都从这里出
        KamiResult parsed = KamiResult.fromMessage(message);
        if (parsed.status == KamiResult.Status.UNKNOWN_BLOCK && r.code >= 500) {
            return new KamiResult(KamiResult.Status.SERVER_ERROR, message);
        }
        return parsed;
    }

    private Response post(String urlStr, String json) {
        Response out = new Response();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection hs = (HttpsURLConnection) conn;
                if (pinnedFactory != null) {
                    hs.setSSLSocketFactory(pinnedFactory);
                    hs.setHostnameVerifier(ANY_HOST_BUT_PINNED);
                }
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(config.connectTimeoutMillis);
            conn.setReadTimeout(config.readTimeoutMillis);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            // 明确不要 gzip 以外的编码，避免部分容器返回 chunked 空体
            conn.setRequestProperty("Accept-Charset", "utf-8");

            byte[] payload = json.getBytes(UTF8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }

            out.code = conn.getResponseCode();
            InputStream is = out.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            out.body = is == null ? "" : readAll(is);
            try {
                out.json = out.body.length() > 0 ? new JSONObject(out.body) : null;
            } catch (Exception ignored) {
                out.json = null; // 网关 502 返回 HTML 时会走到这里
            }
        } catch (IOException e) {
            out.ioError = true;
            out.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Exception e) {
            out.ioError = true;
            out.error = e.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), UTF8);
    }

    private static final class Response {
        int code;
        String body = "";
        JSONObject json;
        boolean ioError;
        String error;
    }

    // ------------------------------------------------------------ cert pinning

    /**
     * 证书锁定：信任链照常校验，但额外要求对端叶子/中间证书的 SHA-256 指纹命中白名单。
     * 只在 HTTPS + 配了 pinnedCertSha256 时启用。
     */
    private static SSLSocketFactory buildPinnedFactory(final String sha256Hex) {
        try {
            final X509TrustManager system = systemTrustManager();
            X509TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // 单向 TLS，客户端不需要
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                    system.checkServerTrusted(chain, authType); // 先做常规链校验
                    if (chain != null && chain.length > 0 && !matches(chain[0], sha256Hex)) {
                        throw new java.security.cert.CertificateException("证书指纹不匹配，可能被中间人劫持");
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return system.getAcceptedIssuers();
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new java.security.SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            Log.e(TAG, "pinning init failed, fall back to default", e);
            return null;
        }
    }

    private static X509TrustManager systemTrustManager() throws Exception {
        javax.net.ssl.TrustManagerFactory tmf =
                javax.net.ssl.TrustManagerFactory.getInstance(
                        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    private static boolean matches(X509Certificate cert, String expectedHex) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() < 2) sb.append('0');
                sb.append(h);
            }
            return sb.toString().equalsIgnoreCase(expectedHex.replace(":", ""));
        } catch (Exception e) {
            return false;
        }
    }

    /** 锁了指纹后主机名不再关键（指纹已唯一确定服务端），放宽以减少自签证书 CN 不匹配的误伤。 */
    private static final HostnameVerifier ANY_HOST_BUT_PINNED = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };
}
