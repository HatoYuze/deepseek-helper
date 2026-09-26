# deepseek-helper

[![Maven Central](https://img.shields.io/maven-central/v/io.github.hatoyuze/deepseek-helper)](https://central.sonatype.com/artifact/io.github.hatoyuze/deepseek-helper)
[![CI Build with Gradle](https://github.com/HatoYuze/deepseek-helper/actions/workflows/gradle.yml/badge.svg)](https://github.com/HatoYuze/deepseek-helper/actions/workflows/gradle.yml)

[English Version](README-en.md) | [简体中文](README.md)

**deepseek-helper** 是一个基于 Kotlin Multiplatform (KMP) 全 `ktor` 技术栈的 [DeepSeek API](https://api-docs.deepseek.com/) 封装库

支持`/chat/completions`的中断、重新生成、自定义 Tool Call、FIM 补全等行为，并为使用提供了简易的 `DSL`语法.

### 快速集成

```kotlin
// build.gradle.kts (commonMain)
dependencies {
    implementation("io.github.hatoyuze:deepseek-helper:0.4.0")
}
```

> **平台**：JVM / Android (minSdk 21) / iOS (arm64, x64, simulator) / macOS (arm64) / Linux (x64, arm64) / Windows (mingw) / JS / WasmJS。  
> **默认引擎**：JVM & Native 使用 `CIO`，Android 使用 `OkHttp`，JS/Wasm 使用 `js`。

### 快速上手
为了调用`deepseek` API 你需要一个 `Deepseek` 实例

> 一个 `Deepseek` 实例的生命周期并无限制，`deepseek-helper` 将会提供内置的 `DeepseekHttpClientPool` 来**重复利用**被启用的 `HttpClient`

`Deepseek` 实例将会包含有必要的包装：使用的 `key`、系统提示词、调用的模型及设置、以及内置的 `tool call` 宿主，当收到 Deepseek 发送的 `tool call` 请求时，`deepseek-helper` 将会按照 `ToolCallHost` 宿主寻找已经注册的 `tool call`，并调用、待运行完后**自动**将调用结果回传给 Deepseek

`ToolCallHost` 是无状态的，它唯一的职责是负责将`tool call`信息寻找到对应的已注册的示例并调用，因此你可以在多个 `StatelessDeepseek` / `Deepseek` 实例中共享一个 `ToolCallHost`

以下将提供一个简单的供应了 `tool call` 的示例

```kotlin
@Serializable
data class WeatherResult(val city: String, val weather: String, val temp: Int)

val ds = deepseek("<Your Deepseek Key>") { // 使用 DSL 语法来构建一个 Deepseek 实例
    prompt = "You are a helpful assistant." // 系统提示词，写在 config 外层
    config {
        thinkingMode = ThinkingMode.Max // 最大推理强度（默认开启思考）
    }
    model { flash() } // deepseek-flash，当前唯一推荐的模型（0.4.0 起默认即它）
    tools {
        tool("get_weather") {
            parameters {
                string("city") { required = true }
            }
            description = "获取指定地点的天气，包含有 weather、temp等信息"
            execute { bag, _ -> // bag 作为获取 agent 传递的参数的容器
                // deepseek-helper 利用 kotlin.serialization 可以自动将 data class 序列化为可读的 Json
                WeatherResult(city = bag.getString("city"), weather = "晴", temp = 25)
            }
        }
    }
}
```

当然，你也可以选择一个简单点的创建办法，例如

```kotlin
val ds = Deepseek("<Your Deepseek Key>") // 直接构建 Deepseek 实例
```
未显式指定模型时默认使用库内硬编码的 `deepseek-flash`，**不会**拉取 `/models` 获取可用模型；如需按账号获取最新模型列表，可调用 `ds.availableModels()` 并通过 `Model.ofModel` 选择。

随后，你可以进行一次聊天的调用:
```kotlin
val response = ds.chatStream("你好，上海现在是什么天气？") 
                .collectResponse() // 这是一个 suspend 函数

println(response.thinkingContent)
println(response.content)
```
> `deepseek-helper` 始终启用 `stream = true` 的调用属性，故我们将监听 SSE 事件并将其转换为 `Flow<ChatChunk>`，需要注意的是，我们并不会在调用`chatStream` 时就即刻发送请求，相反的，只有对 `Flow<ChatChunk>` 收集时才会实际发送请求.

对于返回的 `Flow<ChatChunk>` 对象，你可以直接使用`.collectResponse()` 收集流，这将挂起直到服务器 SSE 事件中止为止（即中断或者是完成了回复）

如果你追求实时性，也可以使用我们内置的声明式函数，例如

```kotlin
fun printlnThinkingContent(content: String) {
    content.lines().forEach { line ->
        println(">  $line")
    }
}

val response = ds.chatStream("你好")
    .onThinking { print("$it") }
    .onContent { print(it) }
    .onToolCall {
        println("🔧 ${it.call.name}(${it.call.arguments})")
    }
    .collectResponse()

if (response.content.isNotEmpty()) {
    println(response.content)
}
println("── ${response.usage.totalTokens} tokens")
```

以上代码实现了一个简易的 CLI 交互，它可以实时响应服务器发送的每一个 SSE 事件.

#### Deepseek 与 StatelessDeepseek 客户端的简述

每一个 `Deepseek` 实例将会持有对话中的所有聊天记录，与之相反，你可以使用 `StatelessDeepseek` 获取无状态、不持有聊天记录的 `Deepseek` 客户端

在其中，每一条信息使用 `Message` 存储数据，其构造参数包含的信息的发送者 `Role` 以及信息的内容

在 `0.4.0` 版本号之后，我们使用 `MessageContent` 对 `Message` 的信息内容进行包装， `MessageContent` 存在两个实现，分别为 `Text`（只包含文本信息）以及 `Parts`(包含有文本`TextPart`、图片`ImagePart`、文件`FilePart`的富文本信息)，对于原本的 `String` 调用将会被**映射**到 `MessageContent.Text` 中进行**包装**.

如需构建 `Deepseek` 持有的聊天记录对象，可以使用内置的 DSL 语法进行创建:

```kotlin
val image = imageOf("photos/cat.jpg")  
// imageOf 接受一个 Any 参数表示源，当其为 `ByteArray` 时将构造为对应的`ImagePart`，并依靠字节流中的魔数判断 MIME 类型
// 此外，你可以传入 `FileSource` 表示读取一个文件，这会**即刻**调用 `readBytes()` 读取信息，因此这可能会阻碍您的协程，同时我们不对 FileSource 进行关闭，其生命周期应当由外部管理器负责
// 特别的，对于 String 存在多种转义模式:
//  - 如果 String 是以 `http` / `https` / `data:` 开头的，将视为外链，不进行任何内联
//  - 反之，将被视为文件路径，将调用 `openFileSource(source).use { it.readBytes() }`，也会**即刻读取其内容**
val messages = buildDeepseekMessages {
    Role.System says "You are a helpful assistant"
// 我们使用 `infix fun Role.says(MessageContent)` 声明
    Role.User says image + "What is its content"
// 对于 `MessageContent` 可以直接使用 `+` 运算符进行扩展
    Role.Assistant says "This image describes a scene that ..."
}  
// 返回 List<Message>，其内容顺序与调用声明信息的顺序一致
```

> 所有涉及 文件/图像上传的操作 将会经由 `DeepseekFiles` 处理，`DeepseekFiles` 的设计类似于 `Deepseek` 客户端，同样存在有可复用池等设计，且不同的token之间互相隔离，准确来说，一个 `DeepseekFiles` 对应的是一个账户所有的文件，而非一个对话亦或整个软件的所有文件.


这一 `List<Message>` 有多种用途，你可以将其用于 `StatelessDeepseek` 的调用对话，例如
```kotlin
val ds = statelessDeepseek("sk-xxx") { ... }
val messages: List<Message> = ...
ds.chatStream(messages).collect { ... }
```
**注意**：如果 `messages` 为历史记录，你可能还需要手动添加最新的用户信息，它的作用是将传入的 message 原封不动地传入 Deepseek API

其中， `StatelessDeepseek` 对其的操作是只读的，在发送信息时我们会提前调用 `toList` 转换为确定的列表后再进行调用.

对于标准的单会话语义`Deepseek`，你可以基于 `List<Message>` 修改 / 替换 `Deepseek` 所持有的聊天记录，以下为一些基本的示例

```kotlin
// 读取完整历史（返回调用时的快照，首条为 system prompt）
val messages: List<Message> = ds.messages // 每次读取都会拷贝一次，高频轮询请用 getMessageCount()
ds.getMessageCount()

// 第一条内容等于该字符串的 user 消息在历史中的下标（未找到返回 -1）
val userIndex = ds.findUserMessageIndex("用一句话介绍你自己")

// 把上下文整体设为任意消息序列（精确替换，不自动附加构造期 system prompt）
ds.replaceHistory(messagesFromDatabase)
// 特别地、当序列为空时，其效果等同与初始化列表（将会创建一个初始元素为 system prompt 的可变列表）

// 清空到只剩构造期 system prompt（无 prompt 时为空历史）
// 其等同于 ds.replaceHistory(emptyList())
ds.clearHistory()

// “重新生成”：截断到目标 user 消息后继续补全
ds.replaceHistory(ds.messages.take(userIndex + 1)) // 始终进行浅拷贝，调用方应保证传入的序列之后不会被外界改动
ds.continueStream() // 从当前历史聊天记录直接调用 API
```
需要注意的是：在 `Deepseek` 实例中，历史操作（`addMessage` / `truncateAt` / `replaceHistory` / `clearHistory`）与历史读取（`messages` / `getMessageCount` / `findUserMessageIndex`）都**不是线程安全的**，不得与活跃流的收集并发调用，其的线程安全职责应由外部调用方保证（如使用 Mutex）

`Deepseek` 实例设计的语义在于单会话的，因此它不允许并发多个 SSE 收集流，当执行**所有将会修改聊天记录的操作** *(如在前一对话未完成就再次发送新的对话（将会启动新的收集流）、外部重载对话历史记录等操作)*时，都会**先中断当前的流**（如果有），准确来说，它会取消流的收集协程并中止底层请求（连接关闭、服务端停止生成），调用方的 `collect` / `collectResponse()` 会抛出
`CancellationException`，按常规取消处理即可，你可使用 `cancelStream`实现相同的效果。

`StatelessDeepseek` 支持并发流：可以同时启动多个 chat/FIM 流，`cancelStream()` 会取消
该实例的全部活跃流；如果只想取消单个任务，建议由调用方直接取消对应的 `collect` 协程。

#### `/responses` API 的支持

在 `Deepseek` 支持了 `/responses` 样式的 `API` 后，我们也内置实现了这一`api`的包装，并将其映射到了标准的 `Deepseek.chatStream` API 中:

你可以在 `config` 中设置这一点:

```kotlin
val response = deepseek("<Your Deepseek Key>") { // 使用 DSL 语法来构建一个 Deepseek 实例
    prompt = "You are a helpful assistant." // 系统提示词，写在 config 外层
    config {
        thinkingMode = ThinkingMode.Max // 最大推理强度（默认开启思考）
        api = DeepseekApi.RESPONSES // 声明将会使用 `/responses` API 作为 `chatStream` 的实现
        enableWebSearch = true // “启用搜索”，调用官方的`web_search`，当前仅在` api = DeepseekApi.RESPONSES` 时生效
    }
    model { flash() }
}
```

这会将 `/responses` 的事件映射到当前支持的 `ChatChunk` 中去:
<details>
<summary>查看具体事件的映射表</summary>

| 事件名称                                                                                                                | 行为                                                                                   |
|-------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `response.created`, `response.in_progress`                                                                              | 无行为                                                                                 |
| `response.output_item.added`, `response.output_item.done`                                                               | 向内部缓存的`toolcall`添加元信息                                                       | 
| `response.content_part.added`, `response.content_part.done`,`response.reasoning_text.done`, `response.output_text.done` | 无行为                                                                                 |
| `response.reasoning_text.delta`                                                                                         | 发送 `ChatChunk.ContentDelta`, 内容对应到 `reasoningContent`                           |
| `response.output_text.delta`                                                                                            | 发送 `ChatChunk.ContentDelta`, 内容对应到 `content`                                    |
| `response.function_call_arguments.done`, `response.custom_tool_call_input.done`                                         | 发送 `ChatChunk.ToolCallRequest`                                                       |
| `response.function_call_arguments.delta`, `response.custom_tool_call_input.delta`                                       | 为内部的 `toolcall` 装填参数                                                           |
| `response.web_search_call.in_progress`, `response.web_search_call.searching`                                            | 无行为                                                                                 |
| `response.web_search_call.completed`                                                                                    | 发送特殊的 `ChatChunk.ToolCallRequest`, 其 `toolcall.name` 为 `_deepseek__web_search`  |
| `response.completed`, `response.incomplete`                                                                             | 发送 `ChatChunk.Done`                                                                  |
| `response.failed`                                                                                                       | 抛出`PipelineException`错误                                                            |

</details>


#### FIM 补全 API（Beta）

`fimStream` 请求 `{baseUrl}/beta/completions`（默认官方地址 `https://api.deepseek.com`），模型使用
`modelForFim`（默认 `deepseek-flash`），并复用 `config` 的 `maxTokens`、
`temperature`、`topP`、`stop`、`includeUsage` 与 `topLogprobs`。

```kotlin
val ds = deepseek("<Your Deepseek Key>") {
    model { flash() } // FIM 与水线模型共用；需要别的模型时直接给 ds.modelForFim 赋值
}

val response = ds.fimStream(
    prompt = "def add(a, b):",
    suffix = "    return a + b",
).collectFimResponse()

println(response.text)
println("消耗 ${response.usage.totalTokens} tokens")
```

#### 消息构建 DSL 的补充说明

DSL 的用法已在上面的「Deepseek 与 StatelessDeepseek 客户端的简述」一节给出，这里只补充几处容易踩到的地方。

**`imageOf` 的来源判定**（顺序固定，`Any` 参数按此表匹配）：

| 传入 | 行为 |
|---|---|
| `ByteArray` | 内联为 base64 data URL，MIME 由**魔数**判定（JPEG / PNG / GIF / WebP，识别不出按 `image/jpeg`） |
| `FileSource`（`Bytes` / `Path`） | 读取后内联；库**不**关闭它 |
| `"http://…"` / `"https://…"` / `"data:…"` | 视为外链或已编码的 data URL，原样透传 |
| 其他 `String` | 视为本地文件路径，读取后内联 |
| JVM / Android 扩展 | `File`、`java.net.URI`（支持 `file:`）、`InputStream` |

**按 `file_id` 引用已上传的图片要用 `imageFileOf(...)`**，不能写 `imageOf("file-api-…")`：字符串在 `imageOf` 里表示 URL 或路径，而 `file-api-…` 两者都不是，会被当成文件路径去读。

```kotlin
val uploaded = ds.files().upload("photos/cat.jpg", "image/jpeg")

val messages = buildDeepseekMessages {
    Role.User says imageFileOf(uploaded.id) + "这张图里有什么？"
}
```

**`+` 两侧都是「内容」**，顺序即拼接顺序；文本在前的写法请用 `MessageContent.textPart`，因为 `"文本" + imageOf(...)` 会被 Kotlin 解析到标准库的 `String.plus(Any?)`，得到的是一个字符串。

```kotlin
imageOf(a) + "描述一下"                      // 图 + 文
imageOf(a) + imageOf(b) + "对比这两张图"      // 连续拼接
MessageContent.textPart("先看图：") + imageOf(a) // 文本块在前
```

> **同步语义**：`imageOf` 读取文件发生在**调用方线程**（库不切换调度器），大图或 UI 线程上请自行 `withContext(Dispatchers.IO)`。读取失败抛 `FileSourceReadException`；来源类型不支持、地址空白、内联字节超过 32 MiB 时抛 `IllegalArgumentException`——不静默失败。
>
> **给调用方的便利**：`imagePartOf(...)` 返回块本身（需要手动插入列表时用），`imageBytesOfResource("/x.jpg")` / `fileSourceOfResource("/x.jpg")` / `imageResourceUrl("/x.jpg")` 用于把 classpath 资源当图片用（JVM / Android）。

#### 图像输入（Vision）的补充说明

三种传图方式在上面的简述里已经出现（内联 base64 / 外链 / `file_id`），这里只补充不便放在示例里的约束。

**按 `file_id` 引用发出的是 `file` 内容块，不是 `image_url` 块**——`MessageContent.imageFile(fileId)` 与 `ContentPart.ImagePart(fileId = …)` 都会序列化为 `{"type":"file","file_id":"file-api-…"}`。把 `file_id` 塞进 `image_url` 块会被服务端以 `400 invalid_request_error: missing field url` 拒绝；反过来说，`image_url` 块只接受 `url`。

**细节级别**用 `ImageUrlDetail` 控制：`Low` 会先缩放到 512×512（更快、更省 token），`High` / `Original` / `Auto` 保留原图（默认 `Auto`，当前等价于 `Original`）；按 `file_id` 引用时该字段被服务端忽略。

**官方限制**（库侧只在能本地判定的部分 fail-fast，见公开常量 `MessageContent.MAX_INLINE_IMAGE_BYTES` = 32 MiB 与 `MAX_IMAGE_URL_LENGTH` = 8192）：

| 限制项 | 数值 |
|---|---|
| 图片格式 | JPEG / PNG / GIF / WebP，**按文件内容判定**，不看文件名与声明的 MIME |
| 单图大小 | 内联 base64 或外链 ≤ 32 MiB；`file_id` ≤ 64 MiB |
| 请求体大小 | 内联数据计入其中，上限 48 MiB |
| 单请求图片数 | ≤ 600；单请求图片总大小 ≤ 64 MiB（不含 `file_id` 图片），含 `file_id` 图片最高 200 MiB |
| 外链 | URL ≤ 8192 字符，且需在 60 秒内可被服务端下载完成 |
| 位置 | **只能出现在 `user` 消息**中，否则**发起请求前**就抛 `IllegalArgumentException` |

> **迁移提示（0.4.0）**：`Message.content` 的读取方式从 `String?` 变为 `MessageContent?`，取文本统一用 `message.content?.asText()`；`Message(Role.User, "hi")` 这类字符串字面量写法仍然可用。`Role.Assistance` 已正名为 `Role.Assistant`，`Model.Pro` / `model { pro() }` 已随 v4 退役删除（改用 `model { flash() }` 或 `model { custom("…") }`）。

#### Files API 的补充说明

`DeepseekFiles` 的定位与隔离语义在上面的简述里已经说明，这里补充具体操作与平台差异。

```kotlin
val files = ds.files()

// 上传：mimeType 仅作 multipart 的 Content-Type 提示，服务端按文件内容判定真实格式
val uploaded = files.upload("photos/cat.jpg", "image/jpeg")          // 文件名默认取路径最后一段
val temp = files.upload(                                              // 带有效期，不传则永久有效
    FileSource.Bytes(jpegBytes), mimeType = "image/jpeg", filename = "cat.jpg",
    options = UploadOptions(expiresAfterSeconds = 3600),              // 3600..2592000（1 小时~30 天）
)

files.retrieve(uploaded.id)                              // 查询单个文件
var page = files.list(after = null, limit = 100)         // 游标分页；limit 取值 1..1000
while (page.hasMore) {
    page = files.list(after = page.lastId, limit = 100)
}
files.delete(uploaded.id)                                // 删除
```

`UploadOptions.expiresAfterSeconds` 与 `list` 的 `limit` 越界会在**调用期** fail-fast（`IllegalArgumentException`），不会白跑一次往返。返回值分别是 `DeepseekFile`（含 `id` / `bytes` / `filename` / `expiresAt` 等）、`FileList`（含 `data` / `firstId` / `lastId` / `hasMore`）与 `FileDeletion`。

关于 `FileSource`：它实现 `AutoCloseable`，可配 `use {}` 保证异常与取消时释放；**路径来源只在 JVM / Android 可用**，Native / JS / Wasm 调用 `readBytes()` 会抛 `UnsupportedOperationException`，请改用 `FileSource.Bytes`（这三个平台上读文件本来就要走平台 API）。

> **官方配额**：单文件 ≤ 64 MiB、文件名 ≤ 512 字符、单用户 25 GiB / 10000 个文件、有效期 1 小时到 30 天。
>
> **取消语义**：`cancelStream()` 只取消对话流，**不影响**文件操作；上传请取消调用它的那个协程。`readBytes()` 是同步且不可中断的，并发上传时每个请求都会在堆上持有一份完整图片字节，请自行用 `Semaphore` 之类限流。
>
> **内存提示**：内联 base64 会留在对话历史里，而每轮工具调用循环都会重新序列化整段历史——实测 32 MiB 图片单次请求的 JSON 编码约分配 300 MiB。大图、或需要在多轮请求里复用的图，请走 `files()` + `file_id`。

### 一些特性

#### HttpClient 池

默认所有客户端共享 `DeepseekHttpClientPool.Global`；每个实例可以通过 DSL 使用独立池
并调整超时与重试参数：

```kotlin
val ds = deepseek("<Your Deepseek Key>") {
    pool {
        config {
            connectTimeoutMillis = 60_000
            maxRetries = 2
        }
    }
}
```

> **IMPORTANT** 当池为 `DeepseekHttpClientPool.Global` 时，`pool { }` 会先复制出
> 实例级池再应用修改，不会影响全局共享配置。

#### 自定义 API 服务供应商（baseUrl）

默认所有请求（chat / models / balance / FIM）都发送到官方地址
`https://api.deepseek.com`。通过 `baseUrl` 可以把客户端指向任意 OpenAI/DeepSeek
兼容的 API 服务供应商（代理、网关或自建端点），`Deepseek` 与 `StatelessDeepseek`
均支持：

```kotlin
// 构造器方式
val ds = Deepseek("<Your Key>", baseUrl = "https://my-provider.example.com/v1")

// DSL 方式
val stateless = statelessDeepseek("<Your Key>") {
    baseUrl = "https://my-provider.example.com/v1"
    model { custom("my-model") } // 第三方供应商通常需要自定义模型 ID
}
```

- `baseUrl` 必须是绝对 `http(s)` 地址，非法值在创建客户端时直接抛 `IllegalArgumentException`
- 支持带路径前缀（如上例的 `/v1`），请求拼接为 `{baseUrl}/chat/completions`
- 尾部 `/` 会被自动去除，`https://host/` 与 `https://host` 等价；不支持 userinfo
  （`https://user@host`）、query（`?…`）与 fragment（`#…`）
- `http://` 明文传输 API Key，仅建议用于本地代理或测试环境
- 不同 `baseUrl` 的客户端使用独立的连接池客户端；连接池按 baseUrl 缓存且无自动驱逐，
  请控制 baseUrl 基数（每个供应商/网关少量配置），并可在适当时调用池的 `close()` 释放资源
- `/models`、`/user/balance` 与 FIM（`/beta/completions`）是否可用取决于供应商，不支持的端点会返回服务端错误

#### Tool Call 管道设计

有关 `Tool Call` 的处理逻辑部分，本项目采用了管道式（pipeline）设计：每一次工具调用都不会被直接执行，而是先进入一条由多个阶段（phase）组成的拦截器链，逐层通过后才真正到达核心执行器。这样一来，鉴权、参数校验、反序列化、重试、超时、日志这类横切逻辑都可以作为"插件"挂在链上，而不必侵入工具自身的业务代码。

一次完整的对话（含工具调用循环）大致如下：

```mermaid
flowchart TD
    A(["ds.chatStream(用户输入)"]) --> B["追加 User 消息到对话历史"]
    B --> C{"还有迭代次数?<br/>iterations < maxToolIterations"}
    C -- 否 --> DONE["发射聚合后的 Done<br/>累计 token 用量 + finishReason"]
    DONE --> SAVE["写入 Assistant 回复"]
    SAVE --> FINISH(["流结束"])
    C -- 是 --> STREAM["发起流式补全请求<br/>SSE 逐块解析"]
    STREAM --> CHUNK["发射 ChatChunk 事件<br/>ContentDelta / ToolCallRequest / Done"]
    CHUNK --> HASTOOL{"本轮响应包含<br/>ToolCallRequest?"}
    HASTOOL -- 否 --> DONE
    HASTOOL -- 是 --> HANDLE["handleToolCalls<br/>写入 assistant.tool_calls 消息"]
    HANDLE --> REG["ToolCallHost.execute<br/>按 call.name 查找执行器"]
    REG --> PIPE["ToolCallPipeline<br/>按阶段执行拦截器链"]
    PIPE --> RESULT["得到 ToolResult<br/>写入 role = tool 消息"]
    RESULT --> EMIT["发射 ToolResultData 事件"]
    EMIT --> WS{"全部为服务端<br/>web_search?"}
    WS -- 是 --> DONE
    WS -- 否 --> C
```

管道内部，每个阶段都可以挂载任意数量的拦截器(组件)；同一阶段内的拦截器(组件)按照FIFO(先注册的优先级更低)的顺序执行。

其中的 `ToolCallHost` 持有所有已经安装的`tool call`函数, 每一个 `Deepseek` 实例之间是独立的，你可以通过 `ChatConfig` 修改相关内容


<details>
<summary>了解如何为 ToolCallPipeline 拦截链提供自定义组件</summary>>

> `io.github.hatoyuze.deepseek.toolcall.pipeline.plugins` 下已经内置了四个插件, 可以使用: `PLUGIN.install(host)` 进行按照
>
> <details>
> <summary>内置插件速览</summary>
>
> | 插件 | 挂载阶段 | 行为                                                                                                                                                  |
> | --- | --- |-------------------------------------------------------------------------------------------------------------------------------------------------------|
> | `RetryPlugin` | `EXECUTE` | 捕捉 `PipelineException` 异常，按指数退避自动重试；对于其他异常则会转换为`PipelineException`抛出；`PipelineException(isRetryable = false)` 不会被重试 |
> | `TimeoutPlugin` | `EXECUTE` | 用 `withTimeout` 包裹内层执行，超时抛出 `PipelineException`                                                                                           |
> | `LoggingPlugin` | 全部阶段 | 记录每个阶段的进入/退出与耗时                                                                                                                         |
> | `SerializationPlugin` | `TRANSFORM` | 为 `TypedToolExecutor` 自动反序列化参数，写入 `ctx.typedParams`                                                                                       |
>
> > 这些插件**默认并不会安装**，你可以在 `tools { }` DSL 块中调用 `retry()` `timeout()` `logging()` 完成安装 (in `io.github.hatoyuze.deepseek.toolcall.dsl.ToolCallBuilderKt`)
>
> **注意: 这些插件只会作用于`toolcall`层次进行重试，并不会影响外部调用流**
> </details>
>
> 每一个管道插件只会在所指定的阶段被调用，且存在一定的调用顺序。
>
> 阶段之间是**顺序执行**的（前一个阶段跑完才进入下一个阶段），而同一阶段内的多个拦截器是**嵌套包裹**的：
>
> ```mermaid
> flowchart LR
>     subgraph PIPELINE["ToolCallPipeline 拦截器链"]
>         direction TB
>         V["VALIDATE<br/>参数校验"] --> A["AUTHORIZE<br/>权限鉴权"]
>         A --> T["TRANSFORM<br/>参数反序列化"]
>         T --> E["EXECUTE<br/>核心执行"]
>         E --> P["POST_PROCESS<br/>后处理"]
>         P --> R["ERROR<br/>收尾阶段"]
>     end
>     CALL["ToolCallHost.execute(call)"] --> PIPELINE
>     PIPELINE --> RESULT["ToolResult"]
> ```
>
> ```mermaid
> sequenceDiagram
>     participant H as ToolCallHost
>     participant OUT as 外层拦截器<br/>(后注册)
>     participant IN as 内层拦截器<br/>(先注册)
>     participant CORE as 核心执行器<br/>(EXECUTE 最内层)
>     H->>OUT: execute(call, ctx)
>     OUT->>IN: ctx.proceed()
>     IN->>CORE: ctx.proceed()
>     CORE-->>IN: ToolResult
>     IN-->>OUT: 返回
>     OUT-->>H: ToolResult
> ```
>
> 如果你想要自行注册一个管道插件，可以参照已有的代码完成编写.
>
> 一个简单的鉴权插件：
>
> ```kotlin
> // import io.github.hatoyuze.deepseek.toolcall.pipeline.* / io.github.hatoyuze.deepseek.toolcall.dsl.toolHost
> class AuthPlugin(private val requiredPermission: String) {
> 
>     fun install(host: ToolCallHost) {
>         // 挂在 AUTHORIZE 阶段：所有工具调用都会先经过这里
>         host.intercept(ToolCallPhase.AUTHORIZE) { ctx ->
>             if (requiredPermission !in ctx.executionContext.permissions) {
>                 // 抛出业务异常：管道会将其转换为 ToolResult.error 回传给模型
>                 throw PipelineException(
>                     "权限不足: 需要 $requiredPermission",
>                     isRetryable = false, // 标记不可重试，避免被 RetryPlugin 反复执行
>                 )
>             }
>             ctx.proceed() // 校验通过，放行给内层
>         }
>     }
> }
> 
> // 先构建 host，再安装自定义插件
> val host = toolHost {
>     tool("get_balance") {
>         description = "查询账户余额"
>         parameters { }
>         execute { _, _ -> """{"balance": 100}""" }
>     }
> }
> AuthPlugin("user:finance").install(host)
> 
> val ds = deepseek("sk-...") {
>     executionContext = ToolExecutionContext("u", "s", permissions = setOf("user:finance"))
> }
> ds.toolHost = host
> ```
>
> 自定义插件需要在 host 构建完成后再 `install`，如上所示。
>
> 需要注意的是： **异常会中断整条调用链**, 拦截器内抛出的异常会被管道捕获，先执行 `ERROR` 阶段的拦截器（可通过 `ctx.error` 拿到原始异常），再转成 `ToolResult.error`；`CancellationException` 会原样向上传播，不会被吞掉或重试。需要"失败重试"或"异常兜底"时，请像 `RetryPlugin` 一样用 `try/catch` 包住 `ctx.proceed()`。
>
> <details>
> <summary>完整示例：记录工具耗时的自定义插件</summary>
>
> ```kotlin
> class TimingPlugin(private val onComplete: suspend (String, Long) -> Unit) {
> 
>     fun install(host: ToolCallHost) {
>         // 挂在 EXECUTE 阶段，作为最后一个安装的 EXECUTE 拦截器，它位于最外层
>         host.intercept(ToolCallPhase.EXECUTE) { ctx ->
>             val start = System.currentTimeMillis()
>             try {
>                 ctx.proceed() // 进入内层（可能是 retry / timeout / 核心执行器）
>             } finally {
>                 onComplete(ctx.call.name, System.currentTimeMillis() - start)
>             }
>         }
>     }
> }
> 
> val host = toolHost {
>     tool("search") {
>         description = "搜索互联网"
>         parameters { string("q") { required = true } }
>         execute { bag, _ -> """{"query":"${bag.getString("q")}"}""" }
>     }
>     retry(maxAttempts = 3) // 先注册 → 更靠近核心执行器
>     timeout(5_000)         // 后注册 → 包裹整个重试循环
> }
> // 最后安装 → 位于 EXECUTE 最外层，测量包含重试/超时在内的完整执行耗时
> TimingPlugin { name, ms -> println("$name 耗时 ${ms}ms") }.install(host)
> ```
>
> </details>
>
</details>

#### 安全性

**密钥（API Key）**
- 密钥只在构造期传入一次，之后作为请求头 `Authorization: Bearer <key>` 发出，**不会**出现在 URL、查询串或日志里；异常消息中的请求头会先剥掉 `Authorization`（`redactedHeaders()`）
- 库**不负责**密钥的保管：请勿硬编码进仓库，也不要写进会随崩溃上报的字符串。需要轮换时新开一个 `Deepseek` 实例即可（密钥是实例级、构造后不可变）
- `baseUrl` 指向**自建/第三方供应商**时，密钥会交给对方；库对 `http://` 不做强制升级，走明文传输由调用方自行承担

**`baseUrl` 的加固**
- 只接受绝对 `http(s)` 地址，且**拒绝** userinfo（`https://user:pass@host`，可用于伪造目标主机）、query 与 fragment —— 这些成分会让「按 host + path 拼接端点」的路由静默错位，因此在客户端构造期就 fail-fast
- 校验失败的异常消息**不回显**原始 URL（userinfo / query 可能夹带凭据）

**日志与 `HttpHook`**
- `HttpHook` 是日志/调试用的扩展点，因此交给它的请求体是**脱敏后**的：内联图片的 base64 data URL 与超长无空白载荷会被替换为 `<redacted N chars>`，整体超过 4096 字符则截断并附 `<truncated N chars>`；请求的**结构**（model / messages / 字段名 / 内容块类型）完整保留
- Files 上传的 multipart 请求体不会进 hook，只有一行摘要（文件名 + 字节数 + `purpose`），**本地路径与文件内容都不写入**
- 会被写进日志的字符串（文件名）在 API 边界就按白名单校验，控制字符 / 引号 / 反斜杠 / 分隔符一律拒绝，避免日志伪造与目录结构外泄

**内容与边界**
- 图片只能出现在 `user` 消息中，且来源受限于 `http(s)` / `data:`；非 user 消息携带图片会在**发起请求前**抛 `IllegalArgumentException`，而不是留下一个必然 400 的往返
- 内联图片体积、外部 URL 长度、分页 `limit`、有效期区间都在本地 fail-fast；服务端配额（单图 ≤ 64 MiB、单用户 25 GiB 等）由服务端裁决
- 请求体序列化时 `explicitNulls = false`，值为 `null` 的字段整条不写出，避免把无意义字段塞进请求

> 需要提醒的是：库**不**做内容审查、不代理图片、不做 SSRF 防护——外链图片由 DeepSeek 服务端去下载，别把内部地址当 `imageUrl` 传进去。

#### 性能特征

**连接复用**：默认所有客户端共享 `DeepseekHttpClientPool.Global`，同一 `baseUrl` 只建一个 `HttpClient`（含连接池）；替换 `pool.config` / `factory` 会关闭并重建旧客户端，因此**运行期不要频繁改池配置**。

**`tools` 数组**：工具定义在每个流开始时取一次（`getDefinitions()` 带缓存），随后**每个请求都要随请求体发出**——工具越多，输入 token 与带宽越高，这与宿主是单例还是每客户端一个无关。

**内联图片是主要的内存开销**（实测数据）：一张 32 MiB 图片编码成 base64 后约 42.7 MiB，单次请求的 JSON 编码约分配 300 MiB（若历史里有 CJK 文本，字符串按 UTF-16 存储会到约 390 MiB），Ktor 再把请求体转成字节还要一份。**工具调用循环会重来一遍**——5 轮就是数百 MiB 级的分配与 5 倍多的 base64 重传。因此：

- 大图、或需要在多轮/多请求复用的图 → `files()` + `file_id`（服务端只存一份，请求里只有一个 id）
- 必须内联时 → 请求之间**复用同一个 `MessageContent` 实例**，不要在循环里反复 `imageDataUrl(...)`
- 内联体积上限 32 MiB 是官方限制，不是"推荐值"；实际可用的舒适区要小得多

**读取与编码的线程归属**：`imageOf` / `FileSource.readBytes()` 与 `files().upload(...)` 里的文件读取都是**同步**的，发生在调用方线程；库不切调度器，也不会替你读第二遍（`FileSource.Bytes` 不拷贝字节，`Path` 每次调用读一次）。UI 线程请显式 `withContext(Dispatchers.IO)`，并发上传请自行限流（每个并发上传在堆上持有一份完整图片）。

**并发下的开销**：`StatelessDeepseek` 可以并发多流，`Deepseek` 是单会话语义（新流会先取消旧流）；`DeepseekFiles` 无状态、可安全并发复用（已由测试固化：20 个并发上传互不串味，8 个并发请求实测不被实例级锁串行化）。

#### 各平台差异

| 能力 | JVM | Android | Native（iOS/macOS/Linux/Windows） | JS / Wasm |
|---|---|---|---|---|
| 默认 HTTP 引擎 | CIO | OkHttp | CIO | js |
| 日志实现 | kotlin-logging（走 SLF4J） | `android.util.Log` | 标准输出 | 浏览器 / Node 控制台 |
| `FileSource.Path` | ✅ 读本地文件 | ✅ 进程可访问路径（`content://` 请先自行读出字节） | ❌ 抛 `UnsupportedOperationException` | ❌ 抛 `UnsupportedOperationException` |
| 图片资源工具（`imageBytesOfResource` 等） | ✅ | ✅ | ❌ | ❌ |
| 主线程阻塞风险 | 有（`readBytes()` 同步） | 有（同上，主线程会 ANR） | 有（同上） | 单线程事件循环，长任务会阻塞整个应用 |
| 测试覆盖 | `jvmTest`（含重压/并发） | `testDebugUnitTest` | `linuxX64Test`（本地可跑） | `jsNodeTest` / `wasmJsNodeTest` |

几点需要说明的：

- **Android** 需要 `INTERNET` 权限；默认引擎是 OkHttp，明文 `http://` 会被系统的 network security config 拦截（`baseUrl` 请用 `https`）。Android 目标的编译依赖本机 SDK，缺失时 Gradle 会自动跳过该目标（不影响其它平台）
- **Native 上没有 `FileSource.Path`**：这不是疏漏，而是 Kotlin/Native 的 metadata 检查拒绝在 `actual` 声明里使用平台数值类型（`ftell` / `fread`），要绕开只能把文件读取挪出 `actual` 文件、代价大于收益。Native 侧读文件本来就要走平台 API（`NSData` 等），读成字节后用 `FileSource.Bytes` 即可
- **JS / Wasm 没有本地文件系统**，同理用 `FileSource.Bytes`；两者都是单线程运行时，一次大图编码会把事件循环占满
- **共享行为一致**：`commonMain` 的逻辑（协议、DSL、内容块、脱敏、fail-fast、历史语义）在所有平台完全相同，差异只集中在文件读取、日志输出与 HTTP 引擎这三处

### License

Apache License 2.0。参见 [LICENSE](LICENSE)。
