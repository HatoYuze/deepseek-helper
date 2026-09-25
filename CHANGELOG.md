# Changelog

本仓库遵循语义化版本（[SemVer](https://semver.org/lang/zh-CN/)）。发布记录见下，最新版本在前。

## [0.3.1] - 2026-09-26

> **⚠️ 兼容性提示（升级前请读）**：本版包含两处**运行期行为变更**（不是签名变更）：
> ① `truncateAt` 越界从「静默无操作」改为抛 `IndexOutOfBoundsException`，且已 `@Deprecated`；
> ② `Deepseek.messages` 从「内部活视图」改为「调用时快照」。API 层面完全向后兼容，
> 但依赖上述旧行为的调用方需要按下面「行为变更 / 兼容性」与「弃用」两节迁移。

> 0.3.0 已发布到 Maven Central，本版在其之上补齐「上下文整体重建」能力：带状态客户端可整段替换/清空
> 历史，无状态客户端可直接传入完整 messages，并修正 `truncateAt` 越界静默失效的问题。

### 新增功能

- **历史整段替换与清空**
  > `Deepseek.replaceHistory(messages)` 一条调用即可把实例上下文整体设为任意 `List<Message>`，
  > `Deepseek.clearHistory()` 清空到只剩构造期 system prompt（无 prompt 时为空历史）。
  > 两者都做防御性拷贝（浅拷贝，传入的消息对象按引用持有），返回后 `getMessageCount()` 与
  > `messages` 立即一致；传入空列表等价于 `clearHistory()`（重置为初始状态）
- **无状态客户端可直接传完整 messages**
  > 新增 `StatelessDeepseek.chatStream(messages, hook)`：本次请求输入 = 构造期 system prompt +
  > 传入列表，不追加 user 消息、调用时即取快照、不写入任何实例状态（请求缓冲在每次收集时新建，
  > 工具调用循环的中间消息同样只存在于该缓冲），适合「从持久化记录重建上下文后发一轮请求」；
  > 「重新生成/继续生成」直接传截断后的完整列表即可，因此未提供同义的 `continueStream(messages)`

### 修复

- `truncateAt` 越界静默失效
  > 旧实现在 `index >= lastIndex` 或 `index < 0` 时静默什么都不做：调用方以为改了上下文、
  > 实际历史原封不动（下游 400 bad request 的隐性来源之一）。现在越界直接抛
  > `IndexOutOfBoundsException`（异常消息含当前 size 与合法范围）；空历史 `lastIndex == -1`，
  > 任何下标都会抛 —— 需要清空请用 `clearHistory()`
- 历史替换可能被活跃流的回滚悄悄撤销
  > 历史操作现在先取消活跃流（单会话语义，与「启动新流先取消旧流」一致），并改为**整体换表**而非
  > 就地修改：被取消流在自己 `finally` 中的回滚只作用于替换前的旧表，不可能覆盖新的历史
- 被取消的流会误删其它来源写入的消息
  > 旧实现按位置截断回滚（`while (size > historyStart) removeAt(lastIndex)`）：当新流在旧流回滚前
  > 已向同一张历史表追加消息时（「启动新流取消旧流」的正常用法），旧流的回滚会把新流的 user 消息
  > 一并删掉。现在按**引用**精确回滚本轮自己追加的消息，不再影响其它来源的写入
- 内部可变列表外泄
  > `Deepseek.messages` 改为返回不可变快照，不再把内部 `MutableList` 直接发布给调用方
- 无状态 `chatStream(messages)` 的请求缓冲泄漏到下一次收集
  > 缓冲改为每次收集时基于调用时快照新建：同一个 Flow 被重复收集（重试 / 多消费者）时，
  > 不会再带上上一轮已提交的 assistant / tool 消息

### 行为变更 / 兼容性

- `truncateAt` 越界从「静默无操作」变为「抛 `IndexOutOfBoundsException`」；`index == lastIndex`
  仍然合法（历史内容不变），保留「结果含 `[0, index]`」的既有契约。**任何** `truncateAt` 调用
  （含 `index == lastIndex` 的空操作）现在都会先取消当前活跃流并重新安装历史表 —— 与其它历史操作一致
- `Deepseek.messages` 由「内部活视图」变为「调用时快照」：读取者不再与库共享可变状态，
  但快照仍是一次无锁拷贝，历史访问的并发契约不变（不得与活跃流的收集并发调用，需调用方串行化）
- `replaceHistory` 不自动前置构造期 system prompt（精确替换）：需要「prompt + 自有消息」时请把
  system 消息自行放进列表；无状态侧重载相反，构造期 `prompt` 始终参与每次请求，
  需要完全按传入列表控制上下文时使用 `prompt = null` 的实例

### 弃用

- `Deepseek.truncateAt(index)` → 迁移到 `replaceHistory(messages.take(index + 1))`（整体替换）
  或 `clearHistory()`（清空）；`@Deprecated` 为 WARNING 级、**方法保留**，
  IDE 的 `ReplaceWith` 提示即为上述写法。`-Werror` / `allWarningsAsErrors` 的下游请显式迁移

### API 治理

- 新增公共成员：`Deepseek.replaceHistory`、`Deepseek.clearHistory`、
  `StatelessDeepseek.chatStream(messages, hook)`；`api/jvm` 与 `api/android` 基线同步刷新
- 未改动 `ChatClient` 接口、`ChatConfig`、`ToolCallHost` 与管道插件语义；未新增依赖
- 补充 `Deepseek` KDoc「线程模型与并发契约」：单会话语义、历史访问需调用方串行化、换表只保证
  引用级原子性、取消是协作式的、`messages` 为快照（读取是 O(n) 拷贝，轮询请用 `getMessageCount()`）

### 测试

- 历史语义：精确替换 / 空列表重置 / 清空 / 变长变短 / 与逐条 `addMessage` 等价 / 防御性拷贝与快照
- `truncateAt`：前缀契约与越界 fail-fast（含空历史与异常消息）
- 无状态重载：请求体逐条一致、连续调用互不影响、同一 Flow 重复收集、工具循环隔离、
  调用时快照且不改调用方列表
- 并发：替换/截断与活跃流竞态（被取消流回滚不得撤销替换）、被取消轮次只回滚自己写入的消息、
  取消回滚固化、快照稳定性
- JVM 重压：200 轮「启动流 → 替换 → 取消」、8 读者与 3000 次替换风暴真正重叠（只允许读到完整历史）

## [0.3.0] - 2026-08-15

### 新增功能

- `Deepseek` / `StatelessDeepseek` 创建时支持 `baseUrl` 参数（构造器与 DSL 两种方式），
  可指向任意 OpenAI/DeepSeek 兼容的 API 服务供应商；chat / models / balance / FIM 请求
  统一走自定义地址，默认仍为官方 `https://api.deepseek.com`
  - 支持带路径前缀（如 `https://host/v1`）与尾部 `/` 归一化；拒绝 userinfo / query / fragment
  - 非法地址（空白 / 非 http(s) / 无主机）在创建客户端时 fail-fast 抛 `IllegalArgumentException`
  - `checkHttpStatus` 异常消息脱敏 `Authorization` 头，避免 API Key 泄漏进日志/崩溃上报

## [0.2.0] - 2026-08-14

自 v0.1.1 以来的变更（`git log v0.1.1..v0.2.0`）。

> **说明**：多平台目标（JVM / Android / iOS / macOS / Linux / Windows / JS / WasmJS）、
> 流式 Chat Completions 与 Responses 兼容格式、工具调用管道、web_search、HttpClient 基础封装等能力
> 自 v0.1.x 起已支持，非本版新增，不再重复列出。

### 新增功能

- **FIM 补全 API（Beta）**
  > 支持 Fill-In-The-Middle 补全，请求发送至 `/beta/completions`，经 `ds.fimStream(...)` 流式收集
  > 标注 `@ExperimentalDeepseekApi`，使用需显式 `@OptIn`
- **HttpClient 池重设计**
  > 移除旧的 `enableBeta` 开关；池按 baseUrl 共享连接，支持可配置的工厂 / 连接与读超时 / 重试策略
  > 通过 `pool { config { } }` DSL 调整；鉴权走每请求 `Authorization` 头，可服务多个 API Key
- **会话式流取消**
  > 重写取消机制为会话（session）驱动：`cancelStream()` 级联中止底层 HTTP 请求
  > 有状态客户端单会话语义（新流自动取消旧流）；无状态客户端并发流互不干扰
- **性能优化**
  > 减少热路径分配（流式累积、工具调用分片），缓存 tool schema 与序列化器

### 修复

- 管道插件组合顺序陷阱
  > 原实现中 `timeout()` 声明在 `retry()` 之前时，超时保护会被静默跳过、永不执行
  > 修复后 `retry` 重试的是其后整条 EXECUTE 链：`timeout` 先声明时对每次重试尝试各自生效，
  > 后声明时作为总预算包裹整轮重试
- `RetryPlugin` 退避与文档不符
  > 原实现首轮实际等待 `2 × baseDelayMs` 且随次数线性增长，已修正为指数序列
  > （第 n 次重试前等待 `baseDelayMs × backoffMultiplier^(n-1)`，默认 500 → 1000 → 2000 ms）
- 文档示例不可编译
  > KDoc 中 `ThinkingMode.Disabled()` 与 `data object` 定义不符，改为 `ThinkingMode.Disabled`
  > 示例中的不安全类型转换 `bag["city"] as String` 统一改为类型安全的 `bag.getString(...)`

### API 治理

- 开启 `explicitApi()`：全部公共声明显式标注可见性，`collectHeaders` 收敛为 internal
- Beta 能力（FIM）统一 `@ExperimentalDeepseekApi` opt-in 标注
- 接入 `binary-compatibility-validator`：提交 `api/` 基线（jvm + android），`apiCheck` 进 CI
- 固定 JDK 17 工具链（`jvmToolchain(17)`）

### 测试

- 新增并发与取消语义测试（会话替换、兄弟流隔离、chat/FIM 并发、历史回滚）
- HttpClient 池并发测试（同/异 baseUrl 共享、close/配置替换竞态、工厂替换失效）
- JVM 压力测试（500 并发流、200 取消风暴、200 快速替换）
- 全目标（JVM / Android / Linux / JS / WasmJS）测试通过

## [0.1.1] - 2026-08-11（Maven Central 已发布）

- 支持 Android target（OkHttp 引擎、`android.util.Log` 日志）

## [0.1.0] - 2026-08-10（Maven Central 已发布）

- 初始版本：KMP 多平台（JVM / Android / iOS / macOS / Linux / Windows / JS / WasmJS）、
  流式 Chat Completions 与 Responses 兼容格式、工具调用管道、HttpClient 封装、web_search
