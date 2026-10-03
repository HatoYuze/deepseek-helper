# Changelog

本仓库遵循语义化版本（[SemVer](https://semver.org/lang/zh-CN/)）。发布记录见下，最新版本在前。

## [0.4.2] - 2026-10-04

> 上游会偶发把模型的**内部工具调用语法**（信封）当作正文下发（`finish_reason=stop`、`tool_calls`
> 为 `null`），官方文档从未定义该语法；触发条件与最小复现在 `README.md` 的
> 「上游工具调用语法泄漏（内部信封）」一节。本版把这类泄漏挡在正文、历史与后续请求之外：
> **纯防御、无公开 API 变更**，无需迁移。

### 新增

- **`ChatConfig.inlineToolCallPolicy`（`@ExperimentalDeepseekApi`）——逃生舱**
  > 三档：`RECOVER`（默认，恢复执行 + 剔除）/ `STRIP`（只剔除、不执行）/ `PASSTHROUGH`（完全不干预，
  > 连回放清洗也不做）。不设置就是修好的默认行为；它是给「应用侧已有自己的解析器」或「需要逐字保真
  > 展示模型输出」的上层的退出通道
- **退役标准写进 KDoc 与 README**
  > 上游 issue 关闭 + 一个发布周期内恢复日志零命中 ⇒ 默认降级为 `STRIP`；再一个周期零命中 ⇒
  > 删除恢复执行的代码路径，仅保留剔除

### 修复

- **内部信封不再作为正文下发**
  > 正文里的信封能完整解析、且工具名在本次请求的 `tools` 里时，恢复成 `ChatChunk.ToolCallRequest`
  > 走既有工具管道执行（鉴权 / 校验 / 重试 / 超时插件照常生效）；其余情况（未注册工具、没有
  > `toolHost`、参数非法、未闭合）一律剔除并记 `ERROR`。思考通道里的信封只剔除、绝不执行
- **流式 delta 切开定界符时不再漏出半截标记**
  > 逐字符匹配定界符，数据不足时扣留而不是退化去匹配更短的变体——双竖线形态内部天然包含单竖线
  > 形态，贪心匹配会把信封从中间切开、把后半段当正文吐出去
- **回放前清洗历史里残留的信封**
  > 只清洗 assistant 的 `content`；`toolCalls[].arguments`、user / tool 消息一律不动（工具参数里
  > 合法出现同名字样属于数据）。避免上游泄漏被当成本会话的历史反复喂回去
- **历史带 tool 轮次但请求不发 `tools` 时留下可诊断日志**
  > 这是公开 issue 里的确定性触发条件（DeepSeek-V3#1678）；库补不出工具定义，因此只记 `ERROR`
  > 并在响应侧兜底
- **恢复出来的调用同样受 `toolChoice` 约束**
  > `ToolChoice.None` 时一个都不恢复执行、`Named(x)` 时只允许 x。恢复是客户端解释出来的调用，
  > 服务端无从否决，因此开发者的工具策略必须由库自己守住
- **取消时不再留下没有配对 tool 结果的 `assistant(tool_calls)`**
  > `handleToolCalls` 的记账改为 `try/finally`：工具挂起时被取消（`cancelStream` / 收集协程取消）
  > 也会把已写入的 assistant 轮次纳入本轮回滚，否则下一次请求会带着「tool_calls 没有 tool 结果」
  > 的非法历史发出去（并发评审 H1）
- **取消不再让会话注册表泄漏**
  > `unregister` 在 `finally` 里跑，那一刻协程常常已处于取消状态，而 `Mutex.withLock` 可取消——
  > 高并发下会拿不到锁就抛出，会话永远留在 `sessions` 里。改为 `NonCancellable` 包裹（并发评审 M2）

## [0.4.1] - 2026-09-27

> 0.4.1 让请求回到 DeepSeek 思考模式的**请求级**规则：请求**带 `tools`** 时，历史里**所有轮次**的
> `reasoning_content` 都必须完整回传（含没有发生工具调用的轮次，官方文档明示缺失即 400：
> `The reasoning_content in the thinking mode must be passed back to the API`）；请求不带 `tools` 时
> 服务端忽略该字段。0.4.0 在装配请求体时无条件剥离了该字段——既违反规则，也把库内历史已经记下的
> 思考内容丢在了请求之外。
> API 层面完全向后兼容（`Message.reasoningContent` 本就存在，只是过去不会被发送，现在会），
> 因此按 patch 发布。
>
> 发布前用线上 key 实测（`deepseek-flash` 与 `deepseek-v4-pro`；流式与非流式；`thinking` 显式开启、
> `reasoning_effort=high`）：**回传后被服务端接受（200）**；而缺失或空串的 `reasoning_content`
> 当前同样被接受，未复现文档所述的 400。即本版是按官方契约收敛到正确形状、并消除历史信息丢失，
> 而不是修一个当前必然复现的中断——升级说明请按此口径理解。
>
> 行为影响：带 `tools` 的多轮请求会把历史里各轮的思考内容一并发送，并**被服务端拼接进上下文**，
> 因此 prompt token 会相应上升；这是官方要求的行为，不是本次修复引入的开销。不带 `tools` 时该字段
> 仍会随历史一起上传（服务端忽略、不计入上下文），这是为了不按 `tools` 分流——那正是本次修掉的
> 错误来源。另外，接到 `HttpHook` 的请求体日志从本版起包含思考内容（脱敏规则不变，仍只看文本形状）。

### 修复

- **思考内容不再在装配请求体时被剥离**
  > 删除内部的 `withoutReasoningContent()`：调用方（如经 `replaceHistory` 传入的持久化历史）放进
  > `Message.reasoningContent` 的内容会原样出现在请求里。规则是请求级的：不带 `tools` 时服务端
  > 忽略该字段，因此无需按 `tools` 是否存在分流
- **流式对话把思考内容写进历史**
  > `streamLoop` 过去只把 `content` 写进历史、丢掉 `reasoning_content`，于是「隔一轮再问」时请求里
  > 就少了这个必需字段。现在每条 assistant 消息——包括工具轮里那条 `tool_calls` 消息——都带上
  > **产生它的那一轮**思考；模型这一轮没有思考时字段保持 `null`（不写空串）
- **公开契约补上回传规则**
  > `Message.reasoningContent` 的 KDoc 与 README 写清「带 `tools` 时必须完整回传、不带 `tools` 时
  > 服务端忽略」，避免自行构造历史（持久化回放、DSL 拼装、无状态调用）的调用方丢掉该字段

## [0.4.0] - 2026-09-26

> **⚠️ 兼容性提示（升级前请读）**：本版包含**三处二进制不兼容变更**，下游**必须重新编译**：
> 1. `Message.content` 的类型由 `String?` 变为 `MessageContent?`（sealed，纯文本或内容块数组）。
>    Kotlin 源码层面大多无需改动（`Message(Role.User, "hi")` 仍可编译，见下），但
>    `copy` / `component2` / `getContent` 的 JVM 签名随之变化，取值请改用 `MessageContent.asText()`
> 2. `Role.Assistance` → `Role.Assistant`（拼写修正，wire 值不变，见「命名修正」一节）
> 3. 删除 `Model.Pro` / `ModelSelector.pro()` / `Model.pro(available)`，默认模型改为
>    `deepseek-flash`（见「模型名更新」一节）
>
> 因此本版按 minor 发布。

> 0.4.0 在 0.3.1 之上加入 DeepSeek 的图像输入能力：对话补全与 `/responses` 都能传图
> （base64 内联 / 外部 URL / Files API `file_id`），并提供完整的 Files 生命周期管理。

### 新增功能

- **消息内容块（图像输入）**
  > 新增 `MessageContent`（`Text` / `Parts`）与 `ContentPart`（`TextPart` / `ImagePart` / `FilePart`），
  > 与官方 wire format 逐字段对应：纯文本序列化为 JSON 字符串，内容块序列化为 JSON 数组。
  > 便捷工厂：`MessageContent.of(text|parts)`、`textPart`、`image(url, detail)`、`imageFile(fileId)`、
  > `fileData(dataUrl, filename)`，以及 base64 编码辅助 `imageDataUrl(mime, bytes)` / `dataUrl(mime, bytes)`
  > （纯 common 实现，基于 `kotlin.io.encoding.Base64`，各平台可用）
- **三种传图方式全部支持（Chat Completions 与 Responses）**
  > ① base64 data URL 内联；② 外部 `http(s)` 图片 URL；③ Files API 上传后的 `file_id`。
  > `/chat/completions` 侧映射为 `text` / `image_url` / `file` 内容块，`/responses` 侧映射为
  > `input_text` / `input_image`（`file` 块的 `file_data` 落到 `input_image.image_url`）
- **`chatStream` 的内容形态重载**
  > `chatStream(content: MessageContent, hook)` 与 `chatStream(parts: List<ContentPart>, hook)`；
  > 原 `chatStream(userContent: String, hook)` 签名与语义不变，历史里仍是纯文本消息
- **Files API（`ds.files()`）**
  > `upload(source|path, mimeType, filename?, options)`、`retrieve(fileId)`、`list(after, limit, order, purpose)`、
  > `delete(fileId)`；返回 `DeepseekFile` / `FileList` / `FileDeletion`。
  > 有效期用 `UploadOptions(expiresAfterSeconds)`（`3600..2592000`，即 1 小时到 30 天；不传则永久有效），
  > 上传时以官方的点号表单字段 `expires_after[anchor]` / `expires_after[seconds]` 发送
- **图片来源的显式资源管理**
  > `FileSource`（`Bytes` / `Path`）实现 `AutoCloseable`，可 `use {}` 保证异常/取消时释放；
  > 路径来源在 JVM / Android 上直接读取，读取失败统一抛 `FileSourceReadException`（不泄漏平台异常）
- **`DeepseekJson`**
  > 公开库内部使用的 JSON 配置（`ignoreUnknownKeys = true`、`explicitNulls = false`），
  > 便于调用方自行构造请求体或落盘历史时得到与库完全一致的形状

### 新增功能（消息构建 DSL）

- **`buildDeepseekMessages { ... }`**：用 `Role.X says content` 声明消息、用 `+` 组合图片与文本，
  返回可直接使用的 `List<Message>`

  ```kotlin
  val image = imageOf("photos/cat.jpg")
  val messages = buildDeepseekMessages {
      Role.System says "You are a helpful assistant"
      Role.User says image + "What is its content"
      Role.Assistant says "This image describes a scene that ..."
  }
  ```

- **`imageOf(source)`**：一步把图片变成内容块，来源判定固定且可预测 ——
  `ByteArray`（MIME 按**魔数**判定，与官方「按内容判断格式」一致）/
  `FileSource` / `http(s)`、`data:` 字符串（原样透传）/ 其他字符串视为本地路径（读取后内联）；
  JVM / Android 另有 `File`、`java.net.URI`（含 `file:`）、`InputStream` 扩展重载。
  另有 `imagePartOf(...)`（返回块本身）与 `imageFileOf(fileId)`（按已上传文件引用，
  字符串在 `imageOf` 里表示 URL/路径，`file-api-…` 两者都不是，因此单独给入口）
- **`+` 组合**：`内容 + 内容`、`内容 + "文本"`、`块 + 内容`；顺序即拼接顺序
  （刻意**不**提供 `"文本" + 内容`：那会被 Kotlin 解析到 stdlib 的 `String.plus(Any?)`，
  提供一个永远不会被调用的扩展是陷阱，文本在前的写法用 `MessageContent.textPart(...) + ...`）
- **JVM 图片资源工具**：`imageBytesOfResource("/sample.jpg")`、`fileSourceOfResource(...)`、
  `imageResourceUrl(...)`，便于把 classpath 里的图片直接喂给 `imageOf` 或 `files().upload`

### 行为变更 / 兼容性（命名修正）

- **`Role.Assistance` → `Role.Assistant`**：枚举常量名拼写修正（`assistant` 才是正确拼写），
  **wire 值不变** —— `@SerialName("assistant")` 一直是对的，协议与请求/响应内容零影响。
  源码与二进制均不兼容，IDE 全局替换 `Role.Assistance` → `Role.Assistant` 即可
- 该常量**无法用 `@Deprecated` 别名平滑过渡**：枚举里两个 `@SerialName("assistant")` 的常量会被
  kotlinx.serialization 的**编译器插件在编译期拒绝**（实测 2.3.21 报
  `Enum class '...' has duplicate serial name 'assistant' in entry '...'`），
  也就是「加个别名」这条路在本库自身就编译不过，因此只能硬改名
  （顺带说明：运行期并不会为此抛异常 —— `PluginGeneratedSerialDescriptor.buildIndices()` 对同名
  条目是**后者覆盖前者**地静默映射，真出现重名会得到「能编译但解码到错误常量」的更隐蔽后果）
- 新增回归断言：`Role.Assistant` 序列化结果必须仍是 `"assistant"`，并同时锚定
  `system` / `user` / `tool` 三个角色的 wire 值，防止将来再出现同类拼写漂移

### 行为变更 / 兼容性（模型名更新）

- **默认模型改为 `deepseek-flash`**：`Model.Flash.id` 由 `deepseek-v4-flash` 变为 `deepseek-flash`
  （官方当前唯一推荐的模型，文本与图像输入都由它承担）。请求里发的 `model` 字段随之变化
- **删除 `Model.Pro`、`ModelSelector.pro()` 与 `Model.pro(available)`**（二进制不兼容，需重新编译）：
  `deepseek-v4-pro` 已被官方退役 —— 实测服务端仍接受该名字，但由 `deepseek-flash` 承接
  （同一请求下两者 token 用量完全一致）
- **`modelForFim` 默认值由 `Model.Pro` 改为 `Model.Flash`**：FIM 端点仍可用，但 v4-pro 不再是
  可选项；该属性本身保留，需要别的模型时直接赋值
- 需要发送退役名字（复现旧行为/对接灰度环境）时用 `model { custom("deepseek-v4-pro") }`；
  注意 `/models` 端点可能滞后、仍列出这些名字，**能否调用**才是判据（`Model.ofModel` 对任何
  服务端返回的名字都能查到）

### 行为变更 / 兼容性

- **请求 hook 收到的请求体会被脱敏**（`HttpHook.onRequest`）：内联图片的 base64 data URL
  替换为 `data:<mime>;base64,<redacted N chars>`，超长的无空白载荷（大段 base64 / opaque token）
  替换为 `<redacted N chars>`，整体超过 4096 字符的请求体截断并附 `<truncated N chars>`；
  脱敏按单趟扫描实现（O(n)），且**保留文档允许的外部 URL**（≤ 8192 字符）不误伤。
  动机：图片字节属于用户私有内容，而 hook 按约定会被写进应用日志；不脱敏就等于把用户照片
  写进日志。请求的**结构**（model / messages / 字段名 / 内容块类型）完整保留，
  因此调试用途不受影响；只依赖「hook 能看到完整原始体」的下游需要改用别的方式取原始流量
- **上传文件名在 API 边界用白名单校验**：长度 ≤ 512（官方上限），且只允许字母、数字与
  ` ._-()+[]!~@#&,;=`（中文等 Unicode 字母照常放行），违规抛 `IllegalArgumentException`
  —— 一次性挡住 CRLF 注入、引号破坏、路径分隔符外泄，并让 multipart 分片头无需任何转义；
  `upload(path, ...)` 的默认文件名对 `/` 与 Windows `\` 都兼容（旧写法会把整条 Windows 路径
  当文件名发给服务端，而 POSIX 上 `\` 是合法文件名字符，因此只在盘符形态下才当分隔符）
- **multipart 分片头不再自己拼 `Content-Disposition`**：ktor 会为每个分片加上
  `form-data; name=<字段名>`，再传一个完整 disposition 会得到
  `form-data; name=file; file; name=file; filename=...`（裸参数 + name 重复），
  严格解析器（Go `mime/multipart`、busboy）会拒绝；现在只补 `filename=` 参数，
  测试逐字断言 `Content-Disposition: form-data; name=file; filename=cat.jpg`
- 序列化失败统一为 `SerializationException`：内容块的不变量（`image_url` / `file_id` 互斥等）
  与字段类型不符在 `KSerializer` 边界被翻译，`catch (SerializationException)` 能兜住全部坏 JSON
- multipart 响应体读取不再吞掉 `CancellationException`（取消继续传播，只把「读不出响应体」
  降级为 hook 无内容）
- **新增本地 fail-fast**（把注定失败的请求挡在本地，而不是等一个 400）：
  - `MessageContent.image(url)`：只接受 `http(s)` 或 `data:`，外部 URL ≤ 8192 字符
  - `MessageContent.dataUrl/imageDataUrl`：单图 ≤ 32 MiB（`MAX_INLINE_IMAGE_BYTES`）
  - 两者都提供公开常量，便于调用方自行比对
- `MessageContent.asText()` 改为单趟扫描（每次请求的每条消息都会调用它，原先会为每条消息
  分配一个中间列表）；Responses 输入转换不再重复做一次角色校验（改为在转换入口扫一次）
- `Message.content` 由 `String?` 变为 `MessageContent?`：
  - 构造：`Message(Role.User, "hi")` 这类**字符串字面量**仍可编译（伴生对象 `invoke` 转换）；
    变量则要显式写 `MessageContent.of(text)`（Kotlin 的隐式转换只对字面量生效）
  - 读取：`message.content == "hi"` 不再成立，请用 `message.content?.asText()`；
    `findUserMessageIndex(text)` 已内部改用 `asText()`，语义不变
- 请求体不再写出值为 `null` 的字段（`explicitNulls = false`）：`content` 为 `null` 的 assistant
  消息不再发出 `"content": null`。这与 0.3.x 实际发出的形状一致，服务端无差异；解码不受影响
- 图片**只能出现在 user 消息**中：非 user 消息携带图片时，两个后端都会在装配请求体、发起网络
  请求**之前**抛 `IllegalArgumentException`（官方对这类请求返回 `400`，库改为本地 fail-fast）
- **按 `file_id` 引用图片时发出的是 `file` 内容块**（而不是 `image_url` 块）：
  `MessageContent.imageFile(fileId)` 与 `ContentPart.ImagePart(fileId = ...)` 序列化为
  `{"type":"file","file_id":"file-api-..."}`。写成
  `{"type":"image_url","image_url":{"file_id":...}}` 会被真实接口以
  `400 invalid_request_error: missing field url` 拒绝 —— 这是用真实 key 跑线上用例时发现并修掉的；
  带来的可见差异是「file_id 形态解码回来是等价的 `ContentPart.FilePart`」
- `ContentPart.ImagePart` 的 `imageUrl` 与 `fileId` 互斥且必须二选一；`ContentPart.FilePart` 的
  `fileId` 与 `fileData` 互斥且必须二选一；`MessageContent.Parts` 不允许空列表 —— 均在构造期抛错
- 未改动 `ChatClient` 的 `chatStream(String, SseHook?)`、`ChatConfig`、`ToolCallHost` 与管道插件语义

### 已知限制

- **内联图片与工具调用循环的内存开销**：内联 base64 会留在对话历史里，而每一轮工具循环都会把
  整段历史重新序列化一次。实测 32 MiB 图片 → 42.7 MiB base64 字符串，单次请求的 JSON 编码
  约分配 300 MiB（含 CJK 文本时约 390 MiB），Ktor 再复制一份约 90 MiB。因此：**大图、或需要在
  多轮请求中复用的图片，请用 `files().upload(...)` + `file_id`**；内联时请在请求之间复用同一个
  `MessageContent` 实例，不要在循环里反复编码。库不代为限制请求体总量
- **`cancelStream()` 不覆盖文件操作**：它取消的是对话流；上传/查询请取消调用它们的协程
- **`FileSource.readBytes()` 是同步且不可中断的**：在 `upload` 里于调用方上下文执行，
  请显式切到 IO 调度器，并自行限制并发上传数（每个并发上传都会在堆上持有一份完整图片）
- `FileSource.Path` 在 **Native（iOS / macOS / Linux / Windows）** 上不受支持：Kotlin/Native 的
  metadata/commonizer 拒绝在 actual 文件里使用平台数值类型（`ftell` / `fread`），
  调用 `readBytes()` 会抛 `UnsupportedOperationException`，请改用 `FileSource.Bytes`；
  JS / Wasm 平台同理（没有本地文件系统）
- 图片的体积与数量限制（单图 base64/URL ≤ 32 MiB、`file_id` ≤ 64 MiB、请求体 ≤ 48 MiB、
  单请求 ≤ 600 张、外部 URL ≤ 8192 字符）只写进 KDoc / README，库侧不代为校验
- 输出侧不含图片：`ChatChunk` / `ChatResponse` 仍只有文本与 `reasoning_content`

### API 治理

- 新增公共类型：`MessageContent`、`ContentPart`、`ImageUrlDetail`、`DeepseekFiles`、
  `DeepseekFile`、`FileList`、`FileDeletion`、`FilePurpose`、`FileOrder`、`UploadOptions`、
  `FileSource`、`FileSourceReadException`、`openFileSource`、`DeepseekJson`
- 新增公共成员：`ChatClient.chatStream(MessageContent, SseHook?)`、`ChatClient.files()`；
  `Deepseek` / `StatelessDeepseek` 各增 `chatStream(parts, hook)` 重载
  （`StatelessDeepseek` 上因 JVM 擦除与 `chatStream(List<Message>)` 同名，标注
  `@JvmName("chatStreamParts")`；`Deepseek` 上没有同名擦除签名，故保持覆写友好的 `open`，
  JVM 名字仍是 `chatStream`。两者从 Kotlin 调用的名字完全一致，只有直接写 JVM 字节码的下游会看到差异）
- `api/jvm` 与 `api/android` 基线已按 0.4.0 重新生成；未新增第三方依赖
  （base64 来自 stdlib，multipart 来自既有 `ktor-client-core`）

### 测试

- 内容块 wire format：纯文本 → 字符串、空串、内容块 → 数组、`image_url` 嵌套对象、
  `detail` 省略规则、`file_id` / `file_data` + `filename`、往返等价
- 非法输入：未知 `type`、缺 `image_url` 对象、`url` 与 `file_id` 同时出现或都缺失、
  `file_id` / `file_data` 互斥、空内容块列表
- Responses 映射：`input_text` / `input_image`（`file_id` 形态不下发 `detail`）、
  多块顺序保持、system 消息不携带文本时不作为 `instructions`
- 客户端：三种 `chatStream` 重载写入历史的内容一致、无状态重载不落状态、
  `findUserMessageIndex` 命中文本块、非 user 消息带图**一次 HTTP 请求都不发**
- Files API（MockEngine 逐字段断言）：multipart 的 `purpose` / 文件分片头（`name` + `filename` +
  `Content-Type`）/ 原始字节、`expires_after[anchor]` 与 `expires_after[seconds]`、
  游标分页 query、`DELETE /files/:id`、错误状态映射、`limit` 越界与空白 `fileId` 的本地 fail-fast
- JVM：`FileSource.Path` 真实文件读取、可重复读取、缺失文件与目录抛 `FileSourceReadException`、
  `use {}` 在异常路径上释放
- 脱敏与文件名边界：data URL 替换、超长不透明载荷替换、超长体截断、普通文本原样通过、
  日志拍平控制字符、Windows/Unix 路径的默认文件名推导、危险文件名与超长文件名被拒
  （异常消息不回显被注入的内容）
- 模型名单：默认模型 id 断言、DSL 的 `flash()`/`custom()`、FIM 请求体里的 model 字段、
  `modelForFim` 默认值与覆盖、退役名字仍可用 `ofModel`/`custom` 查到或发出
- 图片地址与体积的本地 fail-fast：`http(s)` / `data:` 之外被拒、外部 URL 超长被拒、
  data URL 不受 8192 限制、内联字节超 32 MiB 被拒；`asText()` 单趟实现在
  「单文本块 / 文本夹图片 / 连续多文本块 / 全空」下的语义
- **夹具位置**：测试图片放在 `src/jvmTest/resources/sample.jpg`（随测试 classpath 提供，
  不再放在项目根目录）
- **DSL 契约**（`MessageDslTest`，21 个用例）：需求里给出的样例写法逐行固化、
  `says` 的返回值不影响追加、`build()` 返回快照、`+` 的四种组合与顺序、
  `imageOf` 的每种来源（含 JPEG/PNG/GIF/WebP 魔数判定、本地路径、`FileSource` 不被关闭、
  `File`/`URI`/`InputStream`、不支持类型与空白地址的报错信息）、资源工具与缺失资源的明确报错
- **真实图片（`src/jvmTest/resources/sample.jpg`，960×788 JPEG）的离线用例**
  （`SampleImageInputTest`，CI 无密钥也跑）：
  - 经 `FileSource` 平台实现读取 → 与直接读取逐字节一致；base64 编码 → 解码回读无损
  - Files API 上传的 multipart 请求体：分片头逐字正确、`Content-Type`、以及正文里能找到
    JPEG 的 `FF D8 FF` 标记（证明二进制没被转义或截断）
  - 真实 chat 请求：内容块形状正确，且 hook 看到的请求体**不含**图片数据（脱敏端到端）
  - 真实文件名经校验通过、路径推导出干净的默认文件名（含 Windows 路径）
- **真实读图的线上用例**（`ImageInputLiveTest`，需要 `DEEPSEEK_API_KEY`，缺失时自动跳过）：
  base64 内联提问与 Files API `file_id` 复用（上传 → 两次引用 → 删除）。
  已用真实 key 验证通过：内联用例 `prompt=485 / completion=250` tokens，
  `file_id` 用例上传 399503 字节并复用同一 `file_id` 提问两次、随后删除；两次都得到了
  对图片内容的正确描述（而不是「我看不到图片」）
  该用例的 `maxTokens` 放宽到 4096：读图会额外消耗图像 token，且模型通常先描述再回答，
  按纯文本问答的 128～1024 会得到 `finish_reason=length` 的半截回复
- **修复测试发现失效**：`CliClient.kt` 的线上套件与新增的线上用例都用了
  `companion object` + `@JvmStatic @BeforeClass`，Gradle 的 JUnit4 扫描会因此**整类丢弃**
  （`No tests found for given includes`）—— 也就是说这些线上用例此前从未被执行过。
  改为实例级 `@Before` + `assumeTrue` 后恢复正常：无密钥时 17 个线上用例显示为 skipped
  （而不是静默消失）
- 既有 101 处 `Message(...)` 调用点按新类型迁移，全部历史 / 并发 / 重压测试保持通过

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
