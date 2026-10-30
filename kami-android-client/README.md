# 卡密系统（xxgkami）安卓端授权模块 —— 四件套

> 目标：首次打开输入卡密并绑定机器码；按时计费卡每次启动后台静默复检是否过期。
> 依赖：**零第三方库**（`HttpURLConnection` + `org.json` + 框架自带 View），minSdk 23。

## 文件与职责

| 文件 | 对应你的四个环节 | 说明 |
| :--- | :--- | :--- |
| `MachineCode.java` | ① 生成机器码 | 多源指纹合成 SHA-256，输出 19 字符 `XXXX-XXXX-XXXX-XXXX`，重装/清数据不变 |
| `ActivationActivity.java` | ② 激活界面 | 纯代码布局（不依赖 XML），显示机器码 + 复制 + 输入卡密 + 立即激活 |
| `KamiApi.java` + `KamiResult.java` | ③ 请求后台 API | POST JSON，错误文案 → 枚举映射（契约见下） |
| `SecureLicenseStore.java` | ④ 本地状态存储 | 自实现 AndroidKeyStore AES-256-GCM，GCM 认证标签即完整性校验，防手改 XML |
| `LicenseGate.java` | 启动门禁 | 首次弹窗；已激活则静默复检 + 离线宽限期 |

## 服务端契约（已在 http://121.40.191.46 实测钉死）

**校验优先级：机器码校验 → 到期校验 → allow_reverify 校验**

| 场景 | HTTP | `message` | `KamiResult` |
| :--- | :--- | :--- | :--- |
| 首次核销（绑机） | 200 | `Card used successfully` | `OK` |
| 同机重复（时间卡，未过期） | 200 | 同上 | `OK` |
| 同机重复（`allow_reverify=0`） | 400 | `该卡密不允许重复验证，验证次数已达上限(1次)` | `VALID` ← **正向信号** |
| 异机 | 400 | `机器码不匹配，该卡密已绑定其他设备` | `BOUND_ELSEWHERE` |
| 已过期（同机） | 400 | `卡密已过期` | `EXPIRED` |
| 卡密不存在 | 400 | `卡密不存在` | `NOT_FOUND` |
| 管理员停用 | 400 | `卡密被停止使用` / `卡密已停用` | `SUSPENDED` |
| 次数卡用尽 | 400 | `卡密次数已用尽` | `EXHAUSTED` |
| 时间+次数卡窗口用尽 | 400 | `本时段使用次数已用尽，请等待下一时段` | `CYCLE_EXHAUSTED` |
| 专属卡配错 api_key | 400 | `该卡密为专属卡密，当前API密钥无法使用` | `APIKEY_MISMATCH` |

要点：
1. **必须 POST JSON**，不要 GET query。高级加密卡 `card_key` 形如 `iv$cipher$sig`，含 `+` `/`，走 URL 编码会损坏。
2. **`device_id` 上限 64、`machine_code` 上限 255**（DB 列宽），本模块机器码 19 字符，安全。
3. 时间卡心跳**不扣次数**，可任意频率调用（仍建议 ≥6h 一次，见 `LicenseGate`）。
4. **不要用高级加密卡给安卓用户**（约 1400 字符，手机上没人输得进去）。后台发卡请勾"简单卡密"。

## 接入（3 步）

```java
// 1) Application.onCreate()
LicenseGate.init(this, new KamiConfig("http://121.40.191.46/api"));

// 2) 启动页 onCreate() 里做门禁（回调保证在主线程）
LicenseGate.checkAsync(this, new LicenseGate.Callback() {
    @Override public void onResult(LicenseGate.Verdict v, KamiResult r) {
        switch (v) {
            case GRANTED:
            case GRANTED_OFFLINE:            // 离线宽限期内放行，界面可提示“未联网校验”
                goToMain(); break;
            case NEEDS_ACTIVATION:
            case DENIED:
                ActivationActivity.start(this, r); finish(); break;
        }
    }
});

// 3) 剩余天数（后端未打补丁时返回 DAYS_UNKNOWN，界面需容忍 -1）
int days = LicenseGate.remainingDays(this);
```

`ActivationActivity` 也可拆成你自己的对话框：只需保留 `LicenseGate.activateAsync(ctx, cardKey, cb)` 这一个调用点。

## 编译要求

| 项 | 值 |
| :--- | :--- |
| minSdk | **23**（AndroidKeyStore AES-GCM `KeyGenParameterSpec` 于 API 23 引入） |
| compileSdk | 28 / 30 均验证可编（`setRollbackResistant` 走反射，不产生编译期方法链接） |
| 依赖 | 无第三方库。`HttpURLConnection` + `org.json` + 框架自带 View |
| 权限 | `INTERNET`（清单里已有） |
| `usesCleartextTraffic` | 走 HTTP 时需要（本 APK 已有）；上 HTTPS 后请关掉 |

## ⚠️ 构建陷阱：必须显式 `-encoding UTF-8`

本模块的中英文混合常量（尤其是 `KamiResult.fromMessage` 里的中文匹配串）在**非 UTF-8 默认平台**上会被误读。实测过这个失败模式：ecj 不带 `-encoding` 时按 GBK 解码源码，产出的 class 里中文全是乱码，`build-check` 照样**零错误通过**，但真机上文案永远匹配不上 → 过期卡被放行、有效卡被拒绝。

- Gradle 用户：`android { compileOptions { encoding = "UTF-8" } }`
- 命令行/ecj：务必 `-encoding UTF-8`
- `build-check.sh` 与 `tests/run-mapping-check.sh` 已内置该参数

## 已完成的验证

| 验证 | 结果 |
| :--- | :--- |
| 编译（compileSdk 30 / 28） | ✅ 23 个 class，0 error 0 warning |
| 字节码引用审计 | ✅ 27 个引用的 `android.*` 类全部存在于 compileSdk；`setRollbackResistant` 仅剩反射字符串 |
| 映射单测 `tests/MappingCheck.java` | ✅ 28/28，输入取自**实测响应原文**（含高级卡 `卡密验证失败: ` 前缀变体） |
| 安全性质断言 | ✅ 过期/换机→硬拒清状态；已达上限→放行；网络失败→可重试**且绝不判硬拒**（断网不会误清授权） |
| 心跳不扣次数 | ✅ 时间卡心跳为原值回写；次数卡已禁用心跳，改走只读 `queryBind` |

## 已知局限（不粉饰）

1. **拿不到权威到期时间**：`/cards/use` 成功响应不含时间字段，`remainingDays()` 在后端补丁上线前恒为 `DAYS_UNKNOWN`，授权存活完全依赖心跳。按 `README-BACKEND-PATCH.md` 加 3 个字段即可解决。
2. **机器码不是机密**：`/public/cards/machine-bind/query` 无鉴权（`SecurityConfig` 第 40 行 `anyRequest().permitAll()`）且回吐明文机器码，因此"一机一码"只是防普通用户多设备共用的便利约束，不构成访问控制。
3. **卡类型靠本地缓存推断**：类型未知时禁发心跳（宁可晚一次复检也不误扣次数），首次激活后通过只读查询补齐。
4. **客户端状态不是不可破**：AndroidKeyStore + GCM 认证标签 + rollbackResistant 能挡手改 XML 与快照回滚，挡不住整机镜像与 Frida 运行时改写。真正的授权判定必须在服务端（当前架构已满足：每次心跳由服务端裁决）。
5. **`ANDROID_ID` 在部分定制 ROM 返回 null**，已用双份持久 ID 兜底，但用户手动卸载仍可能清掉外部镜像 → 机器码变化，需走后台解绑。

## 生产环境必须改的 3 件事

1. **上 HTTPS** 并在 `KamiConfig` 里启用证书锁定（`pinFingerprint`），否则卡密在公网明文可窃。
2. **`Base URL` 别硬编码在 APK**，放 `BuildConfig` 或服务端下发；当前为可读性写在构造函数参数里。
3. **`allow_reverify` 建议保持 0**：心跳走 `VALID` 分支时服务端**无任何状态写入**（到期与机器码校验都在它之前），比 `200` 更干净、也更省写入。
