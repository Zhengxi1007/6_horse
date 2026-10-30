# kami 后端补丁：让安卓端能显示真实剩余天数

## 1. 为什么需要这个改动

安卓端心跳走 `POST /api/cards/use`，它的**成功响应体只有 `{"success":true,"message":"Card used successfully"}`，不含任何时间信息**。
于是界面无法显示"剩余 12 天"，客户端只能把本地到期记为"未知"（`SecureLicenseStore.expiryMillis() == 0`），完全靠心跳活着。

后果：一旦设备长期断网，客户端**不知道自己何时过期**，只能靠离线宽限期兜底 —— 宽限期给短了误伤正常用户，给付长了过期用户白用。

后端已经有权威到期时间，只是没往公开查询接口里放：`CardService#getEffectiveTimeCardExpire(Card)` 已能同时兼顾普通卡（`cards.expire_time`）与高级卡（`card_status.expire_time`），且 `getPublicMachineBindStatus()` 已经调用过 `resolveCardForPublicMachineOps()` 拿到了这张卡。

**改动面：一个方法里加两个字段。**

## 2. 补丁

文件：`backend/src/main/java/org/xxg/backend/backend/service/CardService.java`
方法：`getPublicMachineBindStatus(String rawInput)`（当前约 2169 行起）

在 `data.put("cardType", card.getCardType());` **之前**插入：

```java
        // 安卓/桌面客户端需要展示剩余时长；/cards/use 成功响应不带时间信息，
        // 故在此补充服务端权威到期时间。永久卡返回 null（客户端按“不判过期”处理）。
        LocalDateTime effectiveExpire = getEffectiveTimeCardExpire(card);
        data.put("expireTime", effectiveExpire);
        data.put("permanent", isPermanentUnit(durationUnitOf(card)));
        if (effectiveExpire != null && !CardService.isPermanentUnit(durationUnitOf(card))) {
            long seconds = Duration.between(LocalDateTime.now(), effectiveExpire).getSeconds();
            data.put("remainingSeconds", Math.max(0L, seconds));
        } else {
            data.put("remainingSeconds", null);
        }
```

字段名沿用本方法既有 camelCase 风格（`machineCode`/`cardType`/`allowSelfUnbind`），**不要**混用 snake_case。`java.time.Duration` 与 `LocalDateTime` 在该文件已 import（`buildOpenApiActivationData` 在用），**不需要加 import**。

> 注意：`getEffectiveTimeCardExpire` 是 `private`，同类的 `getPublicMachineBindStatus` 可直接调用，无需改可见性。

## 3. 这个接口是无鉴权的 —— 新增字段会不会泄露？

`SecurityConfig` 里是 `.anyRequest().permitAll()`（第 40 行），`/public/cards/**` 无需登录。现状已经回吐 `machineCode` 明文。

**新增的 3 个字段不引入新的泄露类别**（拿到卡密串的人本来就能确认这张卡存在、绑在哪台机器），但**到期时间**属于订阅状态，比机器码更敏感一点。因此建议同批做收紧：

### 3.1 立刻可做（零风险，强烈建议）
发卡时把 `allow_self_unbind = 0` 且 `require_device_unbind = 1`：
- `allow_self_unbind=0` → 自助解绑接口直接抛"该卡密不支持自主解绑"（这条我核对过真实实现，之前我误判为"任意人可解绑"，更正）；
- `require_device_unbind=1` → 解绑必须提供与原设备码一致的 `machine_code`。

两者同时开启后，仅凭卡密串**无法解绑**。

### 3.2 建议做：machineCode 不再无条件回吐
`getPublicMachineBindStatus` 当前逻辑是"只要没开 requireDeviceUnbind 就返回明文机器码"。改成**只返回掩码，不再输出明文**：

```java
        if (bound) {
            // 原文：data.put("machineCode", mc);   ← 仅凭卡密串即可读出对端机器码
            data.put("machineCodeMasked", maskMiddle(mc));   // A1B2-****-****-0718
        }
```

即：删掉 `if (!requireDevice) { data.put("machineCode", mc); }` 这行，换成上面只回 `machineCodeMasked` 的写法（不再保留明文键，否则掩码形同虚设）。
配合一个工具方法：
```java
    private static String maskMiddle(String mc) {
        if (mc == null || mc.length() < 8) return "****";
        return mc.substring(0, 4) + "-****-****-" + mc.substring(mc.length() - 4);
    }
```
> 若前端"自助解绑页"依赖明文机器码做展示，改为展示掩码 + 让用户从本机 App 内复制，效果等价且不外部泄露。

### 3.3 建议做：给公开查询加限流
这是**唯一的卡密枚举/探测面**。按 IP 限次（如 10 次/分钟）即可挡住批量探测。当前无限流；服务器上已能看到 `217.156.67.158` 之类的 zmap 扫描打到 80 端口。

## 4. 安卓端如何消费（**已接好，补丁上线即自动生效**）

`KamiResult` 已带 `remainingSeconds` 字段，`KamiClient.queryBind()` 已解析 `remainingSeconds`，
`LicenseGate.resolveExpiry()` 已在激活成功后调用只读查询取服务端权威值：

```java
// LicenseGate.resolveExpiry(...) 现有实现
KamiResult q = client.queryBind(cardKey);           // 只读，不扣次数、不写状态
if (q.remainingSeconds != null && q.remainingSeconds > 0) {
    return System.currentTimeMillis() + q.remainingSeconds * 1000L;
}
return 0L;   // 后端未打补丁 → 0 = 不按本地时间判过期，仅靠心跳判活
```

因此：
- **不打补丁也能跑**：`remainingSeconds == null` → 到期记为 0 → `remainingDays()` 返回 `DAYS_UNKNOWN`，界面需容忍 -1，授权存活完全交给心跳；
- **打了补丁不用改客户端**：`remainingDays()` 直接返回真实天数，本地到期判定与心跳形成双保险（管理员后台加时后，本地过期也会在下次启动经 `refreshFromServer` 自愈）。

验收方式（一行 curl，看是否出现新字段）：

```bash
curl -s -X POST http://121.40.191.46/api/public/cards/machine-bind/query \
  -H 'Content-Type: application/json' \
  -d '{"card_key":"<你后台的卡密>"}'
# 期望新增： "expireTime":"2026-11-23T15:46:56", "permanent":false, "remainingSeconds":2591000
```

## 5. 顺带记录的其他后端事实（本轮已核实，未改代码）

| 项 | 结论 |
| :--- | :--- |
| `/v1/activate`、`/v1/health`、`/v1/ping` | **线上 jar 里没有**（实测 `No static resource`），源码里有 → 部署的 `app.jar` 落后于 `backend/src`。要用心跳以外的"带时间的核销响应"就得先重建部署。 |
| 时间卡心跳是否扣次数 | **不扣**。`useAdvancedCard` 里 `newCount` 只在非时间卡分支 `-1`，时间卡原值回写。我最初判断"心跳会把月卡耗死"是错的，已更正。 |
| 次数卡心跳 | **每次真扣 1 次**（`redeemCard` count 分支无条件 `-1`）。客户端因此对次数卡禁用心跳，改用只读查询。 |
| 校验优先级 | 机器码校验 → 到期校验 → `allow_reverify` 校验。全部实测复现。 |
| `server.port=8080` 无 TLS + CORS `allowed-origins` 仅 localhost | 卡密在公网明文传输，安卓端必须上 HTTPS 并启用证书锁定（客户端 `KamiConfig.pinnedCertSha256` 已支持）。 |
| 生产 DEBUG 日志 | `backend` 容器在打印 `LoginResponse` 对象内容到 stdout，含 token 的风险，建议把该 logger 降到 INFO。 |
| nginx | 日志里出现过 `GET http://43.248.3.158:22222/` 被响应 200 的绝对形式 URI，疑似开放代理，值得单独确认。 |
