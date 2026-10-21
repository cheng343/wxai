# wxai —— 微信 AI 自动回复（LSPosed 模块）

收到**白名单**会话的文本消息 → 调用 **OpenAI 兼容接口**（DeepSeek / OpenAI / 任意兼容网关）生成回复 → 通过微信原生 `NetSceneSendMsg` 链路发回。

白名单不再手填 `wxid`：模块在微信进程内直接读会话表，把**你微信里现有的单聊和群聊**列出来，在界面上勾选即可。

> 只支持 **微信 8.0.76**（versionCode `3141` / `3120`）。其它版本会打警告并尝试照常 hook，混淆名不对就会失败。

## 特性

- **Compose Material3 设置界面**（WeKit 风格）：分组卡片、点击行弹窗编辑、改动即时保存，无保存按钮
- **自动枚举会话/群聊**：从 `rconversation` + `rcontact` 读出全部会话（备注 > 昵称 > wxid），支持搜索、单聊/群聊筛选、多选
- 白名单精确匹配（`wxid` 或 `xxx@chatroom`），默认 **不回复任何人**
- 群聊默认只在似乎被 @ 时回复（可关）
- 每会话冷却时间 + 每小时上限 + 消息去重（双 hook 点不会重复回复）
- 回复延迟随机化（模拟真人打字间隔）
- 模型不可用时静默失败并写日志，不影响微信本身
- 手机端直连模型接口，API Key 只存在本机 SharedPreferences

## 安装

1. 手机需已 Root 并安装 **LSPosed**（Magisk / KernelSU 模块）。
2. 安装 `app-debug.apk`（当前版本 1.1，约 15 MB）。
3. 打开 **LSPosed 管理器 → 模块** → 启用「微信 AI 助手」。
4. **作用域**只勾选 **微信**（`com.tencent.mm`）。
5. 强制停止微信，再打开微信（模块在 `Application.attach` 时挂载）。
6. 打开「微信 AI 助手」App：填 AI 服务配置 → 在「白名单会话」卡片点「管理白名单」→ 勾选要自动回复的单聊/群聊。
7. 打开「启用自动回复」开关。

> 会话列表需要**微信进程正在运行**（模块加载后才会把列表推给 App）。列表读不到时卡片会显示具体原因并可重试。

> 注意：从本地 build 与 CI build 得到的 APK **签名不同**（各自使用 debug key），互相覆盖安装会失败，需先卸载。

## 配置项

| 字段 | 说明 |
|---|---|
| 启用 | 总开关，默认关 |
| Base URL | OpenAI 兼容地址，如 `https://api.deepseek.com/v1` |
| API Key | 对应服务的 key（弹窗里可切换明文） |
| 模型 | 如 `deepseek-chat`、`gpt-4o-mini` |
| 系统提示词 | 人设/风格，默认「口语化、简短、两句话内」 |
| 白名单 | **从会话列表勾选**；空 = 不回复任何人 |
| 我的昵称 | 群聊判定「@我」用；留空则群消息含 `@` 就触发 |
| 群聊仅 @ 时回复 | 默认开 |
| 冷却(秒) | 同一会话两次自动回复最小间隔，默认 20 |
| 每小时上限 | 单会话每小时最多回复次数，默认 30 |
| 回复延迟 min/max(ms) | 默认 800–2500 |
| 最大回复字数 | 超出截断，默认 300 |
| temperature | 0–2，默认 0.7 |
| 调试日志 | 微信侧命中/发送细节写 Xposed 日志 + Toast，默认开 |

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
| 内核门面 | `hm0.j1.u()`（static）→ CoreStorage `hm0.b0` |
| 库包装 | `ka5.b0`（SqliteDBWrapper，内有 `com.tencent.wcdb.database.SQLiteDatabase`） |

**回复流程**：`H9` after-hook 拿 `MsgInfo` → 反射读字段 → 过滤（文本/非自己/白名单/@/冷却/去重）→ 后台线程调模型 → `y11.r0` 构造场景 → `r1.g()` 入队。

**会话枚举流程**（`ConversationBridge.kt`，跑在微信进程）：

1. 轮询 `hm0.j1.u()` 直到微信内核初始化完成，拿到 CoreStorage；同时 hook 它做兜底
2. 按 WeKit 同款反射路径从中挖出 wcdb `SQLiteDatabase` 明文句柄：先找声明类型为 `ka5.b0` 的字段，找不到就扫所有字段里"无参且返回 `SQLiteDatabase`"的方法
3. 执行
   `SELECT c.username, c.unReadCount, r.nickname, r.conRemark, r.type FROM rconversation c LEFT JOIN rcontact r ON r.username = c.username ORDER BY c.conversationTime DESC`
4. 结果 JSON 通过 `ContentResolver.update(content://dev.cheng.wxai.config/conversations)` 推给模块 App（`ConfigProvider` 校验调用方 uid 必须是微信或自身）

**刷新链路**：App 发广播 `dev.cheng.wxai.ACTION_LIST_CONVERSATIONS`（`setPackage("com.tencent.mm")`）→ 微信进程动态 receiver 重查 → 再写回 Provider。App 侧只读缓存，从不直接碰微信数据文件。

## 换微信版本怎么办

1. 从 `https://github.com/Ujhhgtg/WeKit/releases/download/Dex-Test/wechat-<版本>-<code>-domestic.json` 下载对应报告。
2. 按同样 key 找 `descriptor`，覆盖 `app/src/main/java/dev/cheng/wxai/DexMap.kt` 里的常量（含 `SUPPORTED_VERSION_CODES`）。
3. 会话枚举用到的 4 个 key：`classMmKernel`、`methodGetStorage`、`classCoreStorage`、`classSqliteDbWrapper`，一并覆盖。
4. 字段名若也变了，同步 `DexMap.F_*`。

## 构建

- **云端**：push 到 `main` 自动跑 `.github/workflows/build.yml`，产物在 Actions 的 artifact `wxai-debug-apk`；打 `v*` tag 会自动建 Release 并附 APK。
- **本地**：需 JDK 17+ 与 Android SDK（platform 34 / build-tools 34.0.0），然后 `build.cmd`（Windows）或 `./gradlew assembleDebug`。
- 界面依赖 Jetpack Compose（BOM 2024.06.00 / Material3 1.2.1），首次构建会拉依赖。

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

会话枚举相关（日志前缀 `WXAI/Conv`）：

- `WXAI/Conv hooked hm0.j1.u()` —— 内核门面 hook 安装
- `WXAI/Conv db handle via field ...` / `accessor ...(): com.tencent.wcdb.database.SQLiteDatabase` —— 成功挖到库句柄
- `WXAI/Conv database ready: ...` —— 句柄可用，紧接着会推一次列表
- `WXAI/Conv enumerated N conversations` / `pushed N conversations` —— 枚举与推送成功
- `WXAI/Conv refresh requested` —— 收到了 App 的刷新广播
- `WXAI/Conv no SQLiteDatabase handle found in ...` —— CoreStorage 结构变了，需要按新版本重找
- `WXAI/Conv kernel not ready after 120s` —— 微信启动后 2 分钟仍未初始化内核（罕见）

常见问题：

| 现象 | 原因 |
|---|---|
| 日志里没有 `[WXAI]` | LSPosed 没启用/作用域没勾微信/微信没重启 |
| 有 `wechat versionName=` 但没有 `hooked ...` | 微信版本不是 8.0.76，映射表不匹配 |
| `config: enabled=false` 或 whitelist=0 | 模块 App 里没开/没勾白名单，或 provider 被拒（查 `query rejected`） |
| App 里会话列表一直为空并报错 | 微信进程没跑 / 模块未生效；把界面上的错误原文对照上面的 `WXAI/Conv` 日志 |
| `skip stale msg` | 补发了历史消息；只对 2 分钟内的新消息生效 |
| `HTTP 401` | API Key 错 |
| `回复失败: ...` | 网络/模型错误，详见 Toast 与日志 |
