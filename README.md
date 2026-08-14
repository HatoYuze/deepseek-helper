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
    implementation("io.github.hatoyuze:deepseek-helper:0.2.0")
}
```

> **平台**：JVM / Android (minSdk 21) / iOS (arm64, x64, simulator) / macOS (arm64) / Linux (x64, arm64) / Windows (mingw) / JS / WasmJS。  
> **默认引擎**：JVM & Native 使用 `CIO`，Android 使用 `OkHttp`，JS/Wasm 使用 `js`。

### 快速上手
为了调用`deepseek` API 你需要一个 `Deepseek` 实例

> 一个 `Deepseek` 实例的生命周期并无限制，`deepseek-helper` 将会提供内置的 `DeepseekHttpClientPool` 来**重复利用**被启用的 `HttpClient`

```kotlin
@Serializable
data class WeatherResult(val city: String, val weather: String, val temp: Int)

val ds = deepseek("<Your Deepseek Key>") { // 使用 DSL 语法来构建一个 Deepseek 实例
    prompt = "You are a helpful assistant." // 系统提示词，写在 config 外层
    config {
        thinkingMode = ThinkingMode.Max // 最大推理强度（默认开启思考）
    }
    model { pro() }
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
未显式指定模型时默认使用库内硬编码的 `deepseek-v4-flash`，**不会**拉取 `/model` 获取可用模型；如需按账号获取最新模型列表，可调用 `ds.availableModels()` 并通过 `Model.ofModel` 选择。

随后，你可以进行一次聊天的调用:
```kotlin
val response = ds.chatStream("你好，上海现在是什么天气？") 
                .collectResponse() // 这是一个 suspend 函数

println(response.thinkingContent)
println(response.content)
```
> `deepseek-helper` 默认调用`stream`流下的API，因而 `chatStream` 将会返回一个 `Flow<ChatChunk>`，对于这个`Flow`你可以直接使用`.collectResponse()` 收集流

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

每一个 `Deepseek` 实例将会持有对话中的所有聊天记录，你可以使用 `ds.truncateAt(index)` 截断聊天记录到指定下标处(不可逆)

> `Deepseek` 实例默认行为将会自动存储聊天记录，如果你不需要聊天记录，可以使用 `StatelessDeepseek` 的**无状态客户端**，其调用逻辑与 `Deepseek` 相似


如果你想要中断流，可以使用 `cancelStream()`。它会取消流的收集协程并中止底层请求
（连接关闭、服务端停止生成），调用方的 `collect` / `collectResponse()` 会抛出
`CancellationException`，按常规取消处理即可。

`Deepseek` 是单会话语义：同一时间最多存在一个活跃流（`chatStream` / `continueStream` /
`fimStream` 均参与），启动新流会先取消旧流，`cancelStream()` 取消当前流。

`StatelessDeepseek` 支持并发流：可以同时启动多个 chat/FIM 流，`cancelStream()` 会取消
该实例的全部活跃流；如果只想取消单个任务，建议由调用方直接取消对应的 `collect` 协程。

#### `/responses` API 的支持

在 `Deepseek` 支持了 `/responses` 样式的 `API` 后，我们也内置实现了这一`api`的包装，并将其映射到了标准的 `Deepseek.charStream` API 中:

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

`fimStream` 固定请求 `https://api.deepseek.com/beta/completions`，模型默认使用
`modelForFim`（默认 `deepseek-v4-pro`），并复用 `config` 的 `maxTokens`、
`temperature`、`topP`、`stop`、`includeUsage` 与 `topLogprobs`。

```kotlin
val ds = deepseek("<Your Deepseek Key>") {
    model { pro() } // 官方 FIM 当前仅支持 deepseek-v4-pro
}

val response = ds.fimStream(
    prompt = "def add(a, b):",
    suffix = "    return a + b",
).collectFimResponse()

println(response.text)
println("消耗 ${response.usage.totalTokens} tokens")
```

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

### License

Apache License 2.0。参见 [LICENSE](LICENSE)。
