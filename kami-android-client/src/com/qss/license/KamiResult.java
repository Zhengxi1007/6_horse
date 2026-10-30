package com.qss.license;

/**
 * 服务端返回的语义化结果。
 *
 * <p>后台所有失败都走 HTTP 400 + {@code {"success":false,"message":"中文文案"}}（高级卡还会再加一层
 * {@code "卡密验证失败: "} 前缀），没有独立错误码，因此这里用<b>包含匹配</b>把文案映射成枚举。
 * 映射表与实测行为一一对应，见 README.md 的契约表。
 */
public final class KamiResult {

    public enum Status {
        /** 核销成功（首次激活即走这里，服务端已写入 expire_time）。 */
        OK,
        /** 复检通过但未写状态：机器码匹配 + 卡未过期，因 allow_reverify=0 被拦在“已达上限(1次)”。 */
        VALID,
        EXPIRED,
        BOUND_ELSEWHERE,
        MACHINE_CODE_REQUIRED,
        NOT_FOUND,
        SUSPENDED,
        EXHAUSTED,
        CYCLE_EXHAUSTED,
        MERGED,
        APIKEY_MISMATCH,
        BAD_FORMAT,
        /** 本地未激活（从未成功绑定过）。 */
        NOT_ACTIVATED,
        /** 网络/超时/证书失败：可重试，按离线宽限期处置。 */
        NETWORK_ERROR,
        /** 非 200/400 或响应不是预期 JSON。 */
        SERVER_ERROR,
        /** 命中未知文案：保守判为不可用，但不清除本地状态，交由心跳重试。 */
        UNKNOWN_BLOCK;

        public boolean isGranted() {
            return this == OK || this == VALID;
        }

        public boolean isRetryable() {
            return this == NETWORK_ERROR || this == SERVER_ERROR;
        }

        /** 明确的服务端否定：应当落回激活界面并清除授权状态。 */
        public boolean isHardDeny() {
            switch (this) {
                case EXPIRED:
                case BOUND_ELSEWHERE:
                case NOT_FOUND:
                case SUSPENDED:
                case EXHAUSTED:
                case CYCLE_EXHAUSTED:
                case MERGED:
                case APIKEY_MISMATCH:
                case BAD_FORMAT:
                    return true;
                default:
                    return false;
            }
        }
    }

    public final Status status;
    /** 服务端原始 message，便于排障（不要直接展示给用户）。 */
    public final String rawMessage;
    /** 仅 bindQuery 使用：服务端回吐的已绑定机器码。 */
    public final String boundMachineCode;
    /** 仅 bindQuery 使用：卡类型 time / count / time_count。 */
    public final String cardType;
    /** 已绑定关系（bindQuery.data.bound）。 */
    public final boolean bound;
    /**
     * 仅 bindQuery 使用：服务端权威剩余秒数（来自 README-BACKEND-PATCH.md 新增字段）。
     * null = 后端尚未打补丁 / 永久卡，客户端据此退回“仅靠心跳判活”。
     */
    public final Long remainingSeconds;

    public KamiResult(Status status) {
        this(status, null, null, null, false, null);
    }

    public KamiResult(Status status, String rawMessage) {
        this(status, rawMessage, null, null, false, null);
    }

    public KamiResult(Status status, String rawMessage, String boundMachineCode,
                      String cardType, boolean bound, Long remainingSeconds) {
        this.status = status;
        this.rawMessage = rawMessage;
        this.boundMachineCode = boundMachineCode;
        this.cardType = cardType;
        this.bound = bound;
        this.remainingSeconds = remainingSeconds;
    }

    public boolean isGranted() {
        return status.isGranted();
    }

    /**
     * 文案 → 枚举。顺序敏感：先判更具体的串。
     * 依据源码 {@code CardService#redeemCard}/{@code useAdvancedCard} 的实际抛出文本，
     * 且在 121.40.191.46 上逐条实测过。
     */
    public static KamiResult fromMessage(String message) {
        String m = message == null ? "" : message;
        Status s;
        // 判定顺序 = 失效方向顺序：任何“过期/换机”字样都优先于“已达上限”，
        // 保证文案歧义或后端将来合并文案时，宁可误拒也绝不把过期卡判成有效。
        if (m.contains("已过期")) {
            s = Status.EXPIRED;
        } else if (m.contains("绑定其他设备") || m.contains("机器码不匹配")) {
            s = Status.BOUND_ELSEWHERE;
        } else if (m.contains("已达上限") || m.contains("不允许重复验证")) {
            // 关键：这条在源码里出现在“机器码匹配 + 未过期”之后
            // （CardService#useAdvancedCard：机器码校验 → 到期校验 → 本条），
            // 所以能走到这里 = 机器码已匹配且未过期 → 视为复检通过。
            s = Status.VALID;
        } else if (m.contains("请提供机器码") || m.contains("必须提供机器码")) {
            s = Status.MACHINE_CODE_REQUIRED;
        } else if (m.contains("不存在")) {
            s = Status.NOT_FOUND;
        } else if (m.contains("停止使用") || m.contains("已停用")) {
            s = Status.SUSPENDED;
        } else if (m.contains("次数已用尽") && !m.contains("本时段")) {
            s = Status.EXHAUSTED;
        } else if (m.contains("本时段使用次数已用尽")) {
            s = Status.CYCLE_EXHAUSTED;
        } else if (m.contains("续期合并")) {
            s = Status.MERGED;
        } else if (m.contains("专属卡密")) {
            s = Status.APIKEY_MISMATCH;
        } else if (m.contains("格式错误") || m.contains("签名验证失败") || m.contains("解密失败")) {
            s = Status.BAD_FORMAT;
        } else {
            s = Status.UNKNOWN_BLOCK;
        }
        return new KamiResult(s, m);
    }

    /** 面向用户的短语（后台原文偏技术，这里给可读版本）。 */
    public String userMessage() {
        switch (status) {
            case OK:
            case VALID:
                return "授权有效";
            case EXPIRED:
                return "卡密已过期，请续费后重新激活";
            case BOUND_ELSEWHERE:
                return "该卡密已绑定其他设备，请先在后台解绑";
            case MACHINE_CODE_REQUIRED:
                return "该卡密需要先绑定机器码";
            case NOT_FOUND:
                return "卡密不存在，请检查输入是否正确";
            case SUSPENDED:
                return "卡密已被停用，请联系管理员";
            case EXHAUSTED:
                return "卡密次数已用尽";
            case CYCLE_EXHAUSTED:
                return "本时段使用次数已用尽，请稍后再试";
            case MERGED:
                return "该卡密已并入其他订阅，无法使用";
            case APIKEY_MISMATCH:
                return "卡密与渠道不匹配";
            case BAD_FORMAT:
                return "卡密格式不正确";
            case NOT_ACTIVATED:
                return "尚未激活";
            case NETWORK_ERROR:
                return "无法连接授权服务器，请检查网络";
            case SERVER_ERROR:
                return "授权服务异常，请稍后重试";
            default:
                return "未获得授权";
        }
    }

    @Override
    public String toString() {
        return status + (rawMessage == null ? "" : "/" + rawMessage);
    }
}
