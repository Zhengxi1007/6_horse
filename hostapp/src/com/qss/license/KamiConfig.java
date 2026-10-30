package com.qss.license;

/**
 * 后台连接配置。
 *
 * <p>生产环境请把 {@code baseUrl} 放到 BuildConfig / 服务端下发，不要写死在源码里。
 */
public final class KamiConfig {

    /** 例：https://your-host/api （注意结尾不要带斜杠；context-path 已在服务端配好 /api） */
    public final String baseUrl;

    /** 可选：后台「API 密钥」。留空则走全局卡密；若卡密是专属卡则必填。 */
    public final String apiKey;

    /** 建连超时（毫秒）。 */
    public int connectTimeoutMillis = 8_000;

    /** 读取超时（毫秒）。 */
    public int readTimeoutMillis = 12_000;

    /** 静默复检最小间隔：距上次成功复检不足该时长则跳过网络，直接用本地状态。 */
    public long heartbeatIntervalMillis = 6L * 60 * 60 * 1000; // 6h

    /**
     * 断网离线宽限期：距上次“服务端确认有效”不超过该时长时，网络异常不锁功能。
     * 按时计费卡建议 48~72h；太短会让用户在地铁/飞机上被打回激活页。
     */
    public long offlineGraceMillis = 72L * 60 * 60 * 1000; // 72h

    /**
     * 可选：证书锁定（SPKI/证书 SHA-256 十六进制，小写无冒号）。
     * 仅 HTTPS 生效；留空 = 不锁定（内网测试可接受，公网发卡必须配）。
     */
    public String pinnedCertSha256;

    public KamiConfig(String baseUrl) {
        this(baseUrl, null);
    }

    public KamiConfig(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.length() == 0) {
            throw new IllegalArgumentException("baseUrl required");
        }
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = (apiKey == null || apiKey.length() == 0) ? null : apiKey;
    }

    /** {@code /api/cards/use} —— 核销 / 激活 / 心跳复检三合一。 */
    public String useCardUrl() {
        return baseUrl + "/cards/use";
    }

    /** {@code /api/public/cards/machine-bind/query} —— 只读查询绑定与到期（不写状态）。 */
    public String bindQueryUrl() {
        return baseUrl + "/public/cards/machine-bind/query";
    }

    private static String trimTrailingSlash(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }
}
