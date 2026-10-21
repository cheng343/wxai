# wxai —— 微信 AI 自动回复（LSPosed 模块）

收到**白名单**会话的文本消息 → 调用 **OpenAI 兼容接口**（DeepSeek / OpenAI / 任意兼容网关）生成回复 → 通过微信原生 `NetSceneSendMsg` 链路发回。

> 只支持 **微信 8.0.76**（versionCode `3141` / `3120`）。其它版本会打警告并尝试照常 hook，混淆名不对就会失败。

## 特性

- 白名单精确匹配（`wxid` 或 `xxx@chatroom`），默认 **不回复任何人**
- 群聊默认只在似乎被 @ 时回复（可关）
- 每会话冷却时间 + 每小时上限 + 消息去重（双 hook 点不会重复回复）
- 回复延迟随机化（模拟真人打字间隔）
- 模型不可用时静默失败并写日志，不影响微信本身
- 手机端直连模型接口，API Key 只存在本机 SharedPreferences

## 安装

1. 手机需已 Root 并安装 **LSPosed**（Magisk / KernelSU 模块）。
2. 安装 `app-debug.apk`。
3. 打开 **LSPosed 管理器 → 模块** → 启用「微信 AI 助手」。
4. **作用域**只勾选 **微信**（`com.tencent.mm`）。
5. 强制停止微信，再打开微信（模块在 `Application.attach` 时挂载）。
6. 打开「微信 AI 助手」App 填配置 → 保存。

> 注意：从本地 build 与 CI build 得到的 APK **签名不同**（各自使用 debug key），互相覆盖安装会失败，需先卸载。

## 配置项

| 字段 | 说明 |
|---|---|
| 启用 | 总开关，默认关 |
| Base URL | OpenAI 兼容地址，如 `https://api.deepseek.com/v1` |
| API Key | 对应服务的 key |
| 模型 | 如 `deepseek-chat`、`gpt-4o-mini` |
| 系统提示词 | 人设/风格，默认「口语化、简短、两句话内」 |
| 白名单 | 一行一个 `wxid` 或群 `xxx@chatroom`；空 = 不回复任何人 |
| 我的昵称 | 群聊判定「@我」用；留空则群消息含 `@` 就触发 |
| 群聊仅 @ 时回复 | 默认开 |
| 冷却(秒) | 同一会话两次自动回复最小间隔，默认 20 |
| 每小时上限 | 单会话每小时最多回复次数，默认 30 |
| 回复延迟 min/max(ms) | 默认 800–2500 |
| 最大回复字数 | 超出截断，默认 300 |
| temperature | 默认 0.7 |
| 调试 Toast | 触发/回复/失败时弹 Toast，默认开 |

**怎么拿 wxid**：给自己发条消息前先在微信里长按目标联系人 → 无。最省事的办法是发一条消息给对方（或用小号），然后在 LSPosed 日志里看 `trigger <- xxx@chatroom` / `trigger <- wxid_xxx` 这一行，把值抄进白名单。也可以先用调试 Toast 观察。

## 工作原理（微信 8.0.76 混淆符号）

全部签名取自 [WeKit](https://github.com/Ujhhgtg/WeKit) 的 Dex-Test 滚动发布
（`wechat-8.0.76-3141-domestic.json`，3120 与 3141 的公共 key 混淆名完全一致）：

| 用途 | 符号 |
|---|---|
| 收消息 | `com.tencent.mm.storage.f9.H9(com.tencent.mm.storage.e9): long`（另有 `I9(e9, boolean): long`） |
| 消息字段 | `field_talker` / `field_content` / `field_type` / `field_isSend` / `field_msgId` / `field_msgSvrId` / `field_createTime`（微信未混淆字段名） |
| 发送场景类 | `y11.r0` ← `Ly11/r0;-><init>(Ljava/lang/String;Ljava/lang/String;IILjava/lang/Object;)V`，参数 `(talker, content, 1, 0, null)` |
| 取队列 | `e01.d9.e()` → `com.tencent.mm.modelbase.r1` |
| 投队列 | `com.tencent.mm.modelbase.r1.g(com.tencent.mm.modelbase.m1): boolean` |

流程：`H9` after-hook 拿 `MsgInfo` → 反射读字段 → 过滤（文本/非自己/白名单/@/冷却/去重）→ 后台线程调模型 → `y11.r0` 构造场景 → `r1.g()` 入队。

## 换微信版本怎么办

1. 从 `https://github.com/Ujhhgtg/WeKit/releases/download/Dex-Test/wechat-<版本>-<code>-domestic.json` 下载对应报告。
2. 按同样 key 找 `descriptor`，覆盖 `app/src/main/java/dev/cheng/wxai/DexMap.kt` 里的常量（含 `SUPPORTED_VERSION_CODES`）。
3. 字段名若也变了，同步 `DexMap.F_*`。

## 构建

- **云端**：push 到 `main` 自动跑 `.github/workflows/build.yml`，产物在 Actions 的 artifact `wxai-debug-apk`；打 `v*` tag 会自动建 Release 并附 APK。
- **本地**：需 JDK 17+ 与 Android SDK（platform 34 / build-tools 34.0.0），然后 `build.cmd`（Windows）或 `./gradlew assembleDebug`。

## 排障

LSPosed 日志里过滤 `[WXAI]`：

- `module loading in com.tencent.mm` —— 模块被加载
- `wechat versionName=8.0.76 versionCode=3141` —— 版本识别
- `hooked com.tencent.mm.storage.f9.H9 (e9)` —— 收消息 hook 成功
- `sender ready: y11.r0` —— 发送链路就绪
- `config: enabled=... whitelist=N model=...` —— 配置读取结果
- `trigger <- talker: 内容` —— 命中，准备回复
- `reply -> talker: 内容 (ok=true)` —— 已入队发送
- `cooldown hit` / `group msg skipped (not mentioned)` / `skip stale msg` —— 被过滤，属正常

常见问题：

| 现象 | 原因 |
|---|---|
| 日志里没有 `[WXAI]` | LSPosed 没启用/作用域没勾微信/微信没重启 |
| 有 `wechat versionName=` 但没有 `hooked ...` | 微信版本不是 8.0.76，映射表不匹配 |
| `config: enabled=false` 或 whitelist=0 | 模块 App 里没保存，或 provider 被拒（查 `query rejected`） |
| `skip stale msg` | 补发了历史消息；只对 2 分钟内的新消息生效 |
| `HTTP 401` | API Key 错 |
| `回复失败: ...` | 网络/模型错误，详见 Toast 与日志 |
