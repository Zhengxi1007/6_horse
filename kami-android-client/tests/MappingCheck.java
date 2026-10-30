import com.qss.license.KamiResult;

/**
 * 离线校验文案 → 枚举映射。输入全部取自 121.40.191.46 的<b>实测响应原文</b>（含高级卡多出的
 * "卡密验证失败: " 前缀变体），不是我自己编的期望值。
 *
 * 运行：见 tests/run-mapping-check.sh
 */
public class MappingCheck {

    private static int fail = 0, pass = 0;

    // 期望：实测场景 -> 状态 -> 是否放行
    static void t(String label, String serverMessage, KamiResult.Status want, boolean wantGranted) {
        KamiResult r = KamiResult.fromMessage(serverMessage);
        boolean ok = r.status == want && r.isGranted() == wantGranted;
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL  " + label
                    + "\n      msg=\"" + serverMessage + "\""
                    + "\n      got=" + r.status + " granted=" + r.isGranted()
                    + "  want=" + want + " granted=" + wantGranted);
        }
    }

    public static void main(String[] args) {
        // ---- 实测 T1：首次核销绑定（HTTP 200，走 success=true，不经过 fromMessage）
        //     这里断言的是：万一被当成失败文案传进来，也应落到 UNKNOWN_BLOCK 而不是误判过期
        t("核销成功文案不可误判", "Card used successfully", KamiResult.Status.UNKNOWN_BLOCK, false);

        // ---- 实测 T2：同机复检（allow_reverify=0）—— 这是心跳的“存活”正向信号
        t("复检-已达上限(简单卡)", "该卡密不允许重复验证，验证次数已达上限(1次)", KamiResult.Status.VALID, true);
        t("复检-已达上限(高级卡前缀)", "卡密验证失败: 该卡密不允许重复验证，验证次数已达上限(1次)", KamiResult.Status.VALID, true);

        // ---- 实测 T3：异机 —— 必须硬否定
        t("异机-绑定其他设备", "机器码不匹配，该卡密已绑定其他设备", KamiResult.Status.BOUND_ELSEWHERE, false);
        t("异机-高级卡前缀", "卡密验证失败: 机器码不匹配，该卡密已绑定其他设备", KamiResult.Status.BOUND_ELSEWHERE, false);

        // ---- 实测 T4：不带机器码
        t("缺机器码", "该卡密已绑定机器，请提供机器码", KamiResult.Status.MACHINE_CODE_REQUIRED, false);

        // ---- 实测 T5：已过期（同机，优先级高于已达上限）
        t("已过期", "卡密已过期", KamiResult.Status.EXPIRED, false);
        t("已过期-高级卡前缀", "卡密验证失败: 卡密已过期", KamiResult.Status.EXPIRED, false);

        // ---- 源码 enumerate 的其余分支
        t("不存在", "卡密不存在", KamiResult.Status.NOT_FOUND, false);
        t("高级卡不存在或伪造", "卡密不存在或伪造", KamiResult.Status.NOT_FOUND, false);
        t("停用-已激活过", "卡密被停止使用", KamiResult.Status.SUSPENDED, false);
        t("停用-未激活", "卡密已停用", KamiResult.Status.SUSPENDED, false);
        t("次数用尽", "卡密次数已用尽", KamiResult.Status.EXHAUSTED, false);
        t("本时段用尽", "本时段使用次数已用尽，请等待下一时段", KamiResult.Status.CYCLE_EXHAUSTED, false);
        t("续期合并", "该卡密已用于续期合并，无法再次使用", KamiResult.Status.MERGED, false);
        t("专属卡", "该卡密为专属卡密，当前API密钥无法使用", KamiResult.Status.APIKEY_MISMATCH, false);
        t("签名被改", "卡密签名验证失败，可能已被篡改", KamiResult.Status.BAD_FORMAT, false);
        t("格式错", "Invalid card format", KamiResult.Status.UNKNOWN_BLOCK, false);
        t("API Key 非法", "Invalid API Key", KamiResult.Status.UNKNOWN_BLOCK, false);
        t("空 message", "", KamiResult.Status.UNKNOWN_BLOCK, false);
        t("null message", null, KamiResult.Status.UNKNOWN_BLOCK, false);

        // ---- 关键顺序断言：同一条响应里若同时出现“已过期”与“已达上限”，服务端是先到期先报，
        //     映射必须判 EXPIRED（绝不能判成 VALID 而放行过期的卡）
        t("过期优先于已达上限", "卡密已过期（该卡密不允许重复验证，验证次数已达上限(1次)）",
                KamiResult.Status.EXPIRED, false);

        // ---- 语义方法断言
        KamiResult net = new KamiResult(KamiResult.Status.NETWORK_ERROR, "SocketTimeoutException");
        check("网络错误可重试", net.status.isRetryable());
        check("网络错误不是硬否定", !net.status.isHardDeny());
        check("网络错误不放行", !net.isGranted());
        KamiResult val = KamiResult.fromMessage("该卡密不允许重复验证，验证次数已达上限(1次)");
        check("VALID 是放行", val.isGranted());
        check("VALID 不是硬否定", !val.status.isHardDeny());
        check("EXPIRED 是硬否定", KamiResult.fromMessage("卡密已过期").status.isHardDeny());

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    private static void check(String what, boolean cond) {
        if (cond) pass++;
        else { fail++; System.out.println("FAIL  断言不成立: " + what); }
    }
}
