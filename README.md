# SmsGatewayForwarder

LSPosed 短信转发模块，收到短信后 POST 到 [cmcc-message-gateway](https://cmcc-message-gateway.hello-web.workers.dev) 的 `/push` 接口。

原项目 [xtjun/XposedForwardSms](https://github.com/xtjun/XposedForwardSms)（2021 年停更）在 LSPosed 2.x + 新 Android 上「能转发但一进设置页就闪退」，这里是重写版。

## 为什么旧版会闪退

旧版设置页踩了三个雷：

| 问题 | 位置 | 后果 |
|---|---|---|
| `Cyanea` 自己 `new` 一个 `Resources` 来换肤 | `ForwardSmsApplication.getResources()` | LSPosed 2.2.0 修好了资源 Hook，自定义 Resources 包装在 Activity 创建阶段被打破 |
| 用 ContentProvider 手写多进程 SharedPreferences | `common/msp/MultiProcessSharedPreferences` | 授权名解析失败直接抛 `IllegalArgumentException`，没有兜底 |
| 读配置时 `(String) value` 强转 | `SettingsFragment.putMsp()` | prefs 里只要有一个非 String 值就 `ClassCastException` —— **这就是「清除数据后就不闪退了」的原因** |

## 这版怎么做的

- **零第三方依赖**：只有 Android SDK 自带 API。没有 AppCompat / Material / Dagger / ButterKnife / GreenDAO / EventBus / Cyanea / OkHttp。
- **配置读取全程类型安全**：`Settings.load()` 对每个字段做类型校验 + 异常兜底，任何一个值坏掉只退回默认值，不会连累整个页面。
- **多进程配置**：用 LSPosed 官方的 `xposedsharedprefs` 机制 + `XSharedPreferences`，不再自己 chmod、不再有 ContentProvider。
- **Hook 跨版本**：不再按 Android 版本写死构造器/方法签名，而是按「方法名 `dispatchIntent` + 首参 `Intent`」模糊匹配，Android 7 ~ 16 通用。
- **异常不外溢**：Hook 里所有异常就地吞掉并记日志，绝不让它冒泡到电话进程。
- **UI 用系统原生控件 + 代码构建**，不走 XML 布局和第三方主题。

## 安装

1. 装 APK
2. LSPosed 里启用「短信转发网关」
3. 作用域勾选 **电话 / 电话服务**（`com.android.phone`）
4. 重启手机
5. 打开 App → 填入 API Token → 点「发送测试消息」

> 旧模块（`com.github.xtjun.xposed.forwardSms`）请禁用或卸载，否则同一条短信会被转发两次。

## 网关接口

```
POST {gateway}/push?token={token}
Content-Type: application/json

{"title":"短信 · 10086","body":"您的验证码是 1234\n时间: 2026-10-24 16:30:00\n卡槽: 卡1"}
```

也支持 `Authorization: Bearer {token}` / `X-API-Key`（本模块默认用 `?token=`）。
连通性自检：`GET {gateway}/health`。

## 模板变量

| 变量 | 含义 |
|---|---|
| `{sender}` | 发送号码 |
| `{content}` | 短信正文 |
| `{time}` | 接收时间 |
| `{slot}` | 卡槽（卡1 / 卡2） |
| `{device}` | 设置页里填的设备名 |

## 构建

仓库自带 GitHub Actions，push 到 `main` 自动出 debug APK（artifact）。

本地构建：

```bash
gradle assembleDebug
```

需要 JDK 17 + Android SDK 34。`app/libs/api-82.jar` 是 Xposed API，`compileOnly` 使用，不进 APK。

## 日志

`logcat -s SmsFwGateway` 或 LSPosed 管理器里的日志，关键字 `[SmsFw]`。
