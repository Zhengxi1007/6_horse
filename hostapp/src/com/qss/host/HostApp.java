package com.qss.host;

import android.app.Application;

import com.qss.license.KamiConfig;
import com.qss.license.LicenseGate;

/**
 * 在这里初始化卡密网关。
 *
 * <p>⚠️ 上线前必须改：把 baseUrl 换成 HTTPS 域名，并填 pinnedCertSha256。
 * 当前是明文 HTTP，卡密会在公网裸传。
 */
public class HostApp extends Application {

    /**
     * 后端根地址。注意末尾保留 {@code /api}：
     * nginx 的 {@code location /api} 会原样转发到 Spring Boot，
     * 客户端实际请求 {@code {baseUrl}/cards/use}。
     */
    private static final String BASE_URL = "http://121.40.191.46/api";

    @Override
    public void onCreate() {
        super.onCreate();

        KamiConfig cfg = new KamiConfig(BASE_URL);
        // 上线前填写证书 SPKI SHA-256（base64），留空则跳过锁定：
        // cfg.pinnedCertSha256 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        cfg.connectTimeoutMillis = 8_000;
        cfg.readTimeoutMillis = 12_000;
        cfg.heartbeatIntervalMillis = 6L * 60 * 60 * 1000;  // 每 6 小时静默复检一次
        cfg.offlineGraceMillis = 72L * 60 * 60 * 1000;     // 断网最多容忍 72 小时

        LicenseGate.init(this, cfg);
    }
}
