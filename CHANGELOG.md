# Changelog

本仓库遵循语义化版本（[SemVer](https://semver.org/lang/zh-CN/)）。发布记录见下，最新版本在前。

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
