# deepseek-helper

[![Maven Central](https://img.shields.io/maven-central/v/io.github.hatoyuze/deepseek-helper)](https://central.sonatype.com/artifact/io.github.hatoyuze/deepseek-helper)
[![CI Build with Gradle](https://github.com/HatoYuze/deepseek-helper/actions/workflows/gradle.yml/badge.svg)](https://github.com/HatoYuze/deepseek-helper/actions/workflows/gradle.yml)


[English Version](README-en.md) | [简体中文](README.md)


**deepseek-helper** is a Kotlin Multiplatform (KMP) library that wraps the [DeepSeek API](https://api-docs.deepseek.com/) using a full `ktor` technology stack.

It supports interruption, regeneration, custom Tool Call behavior, FIM completion, and more, and provides a simple DSL syntax for usage.

### Quick Integration

```kotlin
// build.gradle.kts (commonMain)
dependencies {
    implementation("io.github.hatoyuze:deepseek-helper:0.4.0")
}
```

> **Platforms**: JVM / Android (minSdk 21) / iOS (arm64, x64, simulator) / macOS (arm64) / Linux (x64, arm64) / Windows (mingw) / JS / WasmJS.  
> **Default engines**: `CIO` for JVM & Native, `OkHttp` for Android, `js` for JS/Wasm.

### Quick Start

To call the DeepSeek API, you need a `Deepseek` instance.

> A `Deepseek` instance has no lifecycle restrictions; `deepseek-helper` provides a built-in `DeepseekHttpClientPool` to **reuse** the enabled `HttpClient`.

```kotlin
@Serializable
data class WeatherResult(val city: String, val weather: String, val temp: Int)

val ds = deepseek("<Your Deepseek Key>") { // Use DSL to build a Deepseek instance
    prompt = "You are a helpful assistant." // System prompt, placed outside the config block
    config {
        thinkingMode = ThinkingMode.Max // Maximum reasoning strength (thinking enabled by default)
    }
    model { flash() } // deepseek-flash, the only currently recommended model (also the default)
    tools {
        tool("get_weather") {
            parameters {
                string("city") { required = true }
            }
            description = "Get weather information for a specified city, including weather, temp, etc."
            execute { bag, _ -> // bag is the container for parameters passed by the agent
                // deepseek-helper uses kotlin.serialization to automatically serialize data classes to readable JSON
                WeatherResult(city = bag.getString("city"), weather = "Sunny", temp = 25)
            }
        }
    }
}
```

Of course, you can also use a simpler creation method, e.g.

```kotlin
val ds = Deepseek("<Your Deepseek Key>") // Directly build a Deepseek instance
```

If no model is explicitly specified, the library defaults to the hardcoded `deepseek-flash` (the only model the API currently recommends; it serves both text and image input). It does **not** fetch available models from `/models`. To retrieve the latest model list for your account, call `ds.availableModels()` and select a model using `Model.ofModel`.

> The `deepseek-v4-pro` / `deepseek-v4-flash` names have been retired: the server still accepts them but serves them with `deepseek-flash` (measured token usage is identical to `deepseek-flash`). The library therefore no longer offers `Model.Pro` or `model { pro() }`; send a legacy name explicitly with `model { custom("deepseek-v4-pro") }` if you really need to. Note that the `/models` endpoint may lag behind and still list these retired names — being callable is the real criterion.

Then, you can start a chat:

```kotlin
val response = ds.chatStream("Hello, what's the weather in Shanghai now?") 
                .collectResponse() // This is a suspend function

println(response.thinkingContent)
println(response.content)
```

> `deepseek-helper` uses the streaming API by default, so `chatStream` returns a `Flow<ChatChunk>`. You can use `.collectResponse()` on this `Flow` to collect the stream.

If you need real-time processing, you can also use our built-in declarative functions, e.g.

```kotlin
fun printlnThinkingContent(content: String) {
    content.lines().forEach { line ->
        println(">  $line")
    }
}

val response = ds.chatStream("Hello")
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

#### A short introduction to the Deepseek and StatelessDeepseek clients

Each `Deepseek` instance holds all chat history in the conversation, whereas `StatelessDeepseek` is a
stateless client that keeps no history.

```kotlin
// Read the whole history (a snapshot taken at call time, starting with the system prompt)
val messages: List<Message> = ds.messages // every read copies; poll getMessageCount() instead
ds.getMessageCount()

// Index of the first user message whose text equals the given string (-1 when not found)
val userIndex = ds.findUserMessageIndex("introduce yourself in one sentence")

// Set the context to any message sequence in one call (exact replacement, no prompt injection)
ds.replaceHistory(messagesFromDatabase)

// Clear down to the construction prompt only (empty history when no prompt is set)
ds.clearHistory()

// "Regenerate": trim to the target user message and continue
ds.replaceHistory(ds.messages.take(userIndex + 1))
ds.continueStream()
```

> `replaceHistory` replaces **exactly**: the construction `prompt` is not prepended, so include the system
> message in the list when you want to keep it (or `clearHistory()` first and `addMessage` one by one).
> An empty list is equivalent to `clearHistory()`. Replacing the history cancels the active stream first,
> and that stream's rollback can never undo the replacement.

> `ds.truncateAt(index)` is deprecated: out-of-range indexes now throw `IndexOutOfBoundsException` where
> older versions silently did nothing. Migrate to `replaceHistory(ds.messages.take(index + 1))` (or
> `clearHistory()`), as shown above.

> History operations (`addMessage` / `truncateAt` / `replaceHistory` / `clearHistory`) and history reads
> (`messages` / `getMessageCount` / `findUserMessageIndex`) are not thread-safe: never call them
> concurrently with an active stream's collection, and serialize them in the application layer
> (Mutex / single-threaded dispatcher) when needed. See the "thread model and concurrency contract"
> section in the `Deepseek` KDoc.

> By default, `Deepseek` instances automatically store chat history. If you do not need history, you can use the **stateless client** `StatelessDeepseek`, which has similar invocation logic to `Deepseek`.
> The stateless client can also take a complete message list directly, for "rebuild the context from
> persisted records and send one request":
>
> ```kotlin
> val ds = statelessDeepseek("sk-xxx") { prompt = null } // leave prompt unset to control the context exactly
> ds.chatStream(messagesFromDatabase).collect { chunk -> /* ... */ }
> ```
>
> This overload appends no user message (`messages` is the whole conversation), snapshots the list at call
> time and keeps no instance state; the construction `prompt` is still prepended to every request, so use
> an instance with `prompt = null` when you do not want an extra system message.

If you want to interrupt a stream, use `cancelStream()`. It cancels the stream
collection coroutine and aborts the underlying request (closes the connection and
stops server-side generation), so the caller's `collect` / `collectResponse()` will
throw a `CancellationException`, which should be handled as a normal cancellation.

`Deepseek` keeps single-session semantics: at most one active stream exists at a time
(`chatStream` / `continueStream` / `fimStream` all participate), a new stream cancels
the previous one, and `cancelStream()` cancels the current stream.

`StatelessDeepseek` supports concurrent streams: multiple chat/FIM streams can run at
once, and `cancelStream()` cancels all active streams on that instance. To cancel a
single task, cancel the corresponding `collect` coroutine from the caller side.

#### Support for the `/responses` API

After DeepSeek added support for the `/responses` API style, we have also implemented a wrapper for this API and mapped it to the standard `Deepseek.chatStream` API:

You can enable this in the `config` block:

```kotlin
val response = deepseek("<Your Deepseek Key>") { // Use DSL to build a Deepseek instance
    prompt = "You are a helpful assistant." // System prompt, placed outside the config block
    config {
        thinkingMode = ThinkingMode.Max // Maximum reasoning strength (thinking enabled by default)
        api = DeepseekApi.RESPONSES // Declare that `/responses` API will be used as the implementation of `chatStream`
        enableWebSearch = true // Enable search via the official `web_search`; currently only effective when `api = DeepseekApi.RESPONSES`
    }
    model { flash() }
}
```

This maps `/responses` events to the currently supported `ChatChunk` types:

<details>
<summary>View the event mapping table</summary>

| Event Name                                                                                                              | Behavior                                                                           |
|-------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| `response.created`, `response.in_progress`                                                                              | No action                                                                          |
| `response.output_item.added`, `response.output_item.done`                                                               | Adds metadata to internally cached tool calls                                      |
| `response.content_part.added`, `response.content_part.done`,`response.reasoning_text.done`, `response.output_text.done` | No action                                                                          |
| `response.reasoning_text.delta`                                                                                         | Emits `ChatChunk.ContentDelta` with content mapped to `reasoningContent`           |
| `response.output_text.delta`                                                                                            | Emits `ChatChunk.ContentDelta` with content mapped to `content`                    |
| `response.function_call_arguments.done`, `response.custom_tool_call_input.done`                                         | Emits `ChatChunk.ToolCallRequest`                                                  |
| `response.function_call_arguments.delta`, `response.custom_tool_call_input.delta`                                       | Populates arguments for internal tool calls                                        |
| `response.web_search_call.in_progress`, `response.web_search_call.searching`                                            | No action                                                                          |
| `response.web_search_call.completed`                                                                                    | Emits a special `ChatChunk.ToolCallRequest` with tool name `_deepseek__web_search` |
| `response.completed`, `response.incomplete`                                                                             | Emits `ChatChunk.Done`                                                             |
| `response.failed`                                                                                                       | Throws a `PipelineException` error                                                 |

</details>


#### FIM Completion API (Beta)

`fimStream` requests `{baseUrl}/beta/completions` (the official
`https://api.deepseek.com` by default). It uses
`modelForFim` (default `deepseek-flash`) and reuses `maxTokens`, `temperature`,
`topP`, `stop`, `includeUsage`, and `topLogprobs` from `config`.

```kotlin
val ds = deepseek("<Your Deepseek Key>") {
}

val response = ds.fimStream(
    prompt = "def add(a, b):",
    suffix = "    return a + b",
).collectFimResponse()

println(response.text)
println("Used ${response.usage.totalTokens} tokens")
```

#### Message Building DSL — supplementary notes

The DSL itself is introduced in the section above; this part only covers the details that do not fit
into an example.

**How `imageOf` dispatches on its source** (fixed order; the parameter is `Any`):

| Input | Behaviour |
|---|---|
| `ByteArray` | inlined as a base64 data URL, MIME sniffed from **magic bytes** (JPEG / PNG / GIF / WebP, falling back to `image/jpeg`) |
| `FileSource` (`Bytes` / `Path`) | read and inlined; the library does **not** close it |
| `"http://…"` / `"https://…"` / `"data:…"` | treated as a remote link or an already-encoded data URL and passed through |
| any other `String` | treated as a local file path, read and inlined |
| JVM / Android extensions | `File`, `java.net.URI` (including `file:`), `InputStream` |

**Referencing an uploaded image by `file_id` requires `imageFileOf(...)`**, not `imageOf("file-api-…")`:
a string in `imageOf` means a URL or a path, and a `file-api-…` id is neither — it would be read as a
file path.

```kotlin
val uploaded = ds.files().upload("photos/cat.jpg", "image/jpeg")

val messages = buildDeepseekMessages {
    Role.User says imageFileOf(uploaded.id) + "What is in this image?"
}
```

**Both sides of `+` are content**, and the order is the order of concatenation. To put text first, use
`MessageContent.textPart`: `"text" + imageOf(...)` is resolved by Kotlin to the standard library's
`String.plus(Any?)` and yields a string.

```kotlin
imageOf(a) + "describe it"                        // image + text
imageOf(a) + imageOf(b) + "compare these two"      // chained
MessageContent.textPart("First: ") + imageOf(a)    // text block first
```

> **Synchronous semantics**: `imageOf` reads files on the **calling thread** (the library never switches
> dispatchers), so use `withContext(Dispatchers.IO)` for large images or on the UI thread. Read failures
> throw `FileSourceReadException`; an unsupported source type, a blank URL, or inline bytes over 32 MiB
> throw `IllegalArgumentException` — never a silent failure.
>
> **Convenience entry points**: `imagePartOf(...)` returns the block itself (for inserting into a list by
> hand), and `imageBytesOfResource("/x.jpg")` / `fileSourceOfResource("/x.jpg")` /
> `imageResourceUrl("/x.jpg")` load classpath resources as images (JVM / Android).

#### Image Input (Vision) — supplementary notes

The three ways to send an image (inline base64 / external URL / `file_id`) are shown above; this part
covers the constraints that do not fit into an example.

**Referencing by `file_id` emits a `file` content block, not an `image_url` block** —
`MessageContent.imageFile(fileId)` and `ContentPart.ImagePart(fileId = …)` both serialize to
`{"type":"file","file_id":"file-api-…"}`. Putting a `file_id` inside an `image_url` block is rejected by
the server with `400 invalid_request_error: missing field url`; conversely, an `image_url` block only
accepts `url`.

**Detail level** is controlled by `ImageUrlDetail`: `Low` downscales to 512×512 (faster, fewer tokens),
while `High` / `Original` / `Auto` keep the original image (`Auto` is the default and currently equals
`Original`). The field is ignored by the server when a `file_id` is used.

**Official limits** (the library only fails fast on what it can decide locally — see the public
constants `MessageContent.MAX_INLINE_IMAGE_BYTES` = 32 MiB and `MAX_IMAGE_URL_LENGTH` = 8192):

| Limit | Value |
|---|---|
| Image formats | JPEG / PNG / GIF / WebP, decided by the **file content**, not by the name or declared MIME |
| Single image size | inline base64 or external URL ≤ 32 MiB; `file_id` ≤ 64 MiB |
| Request body size | inline data counts toward it; limit 48 MiB |
| Images per request | ≤ 600; ≤ 64 MiB in total excluding `file_id` images, up to 200 MiB including them |
| External URL | ≤ 8192 characters, and it must be downloadable by the server within 60 seconds |
| Placement | **user messages only** — anywhere else the library throws `IllegalArgumentException` **before** sending a request |

> **Migration notes (0.4.0)**: `Message.content` is now `MessageContent?` instead of `String?`, so read
> text with `message.content?.asText()`; string literals such as `Message(Role.User, "hi")` still work.
> `Role.Assistance` has been renamed to `Role.Assistant`, and `Model.Pro` / `model { pro() }` were removed
> when the v4 models were retired (use `model { flash() }` or `model { custom("…") }`).

#### Files API — supplementary notes

What `DeepseekFiles` is and how it is isolated is explained above; this part covers the operations and
the platform differences.

```kotlin
val files = ds.files()

// Upload: mimeType is only the multipart Content-Type hint, the server decides the format from content
val uploaded = files.upload("photos/cat.jpg", "image/jpeg")          // filename defaults to the last path segment
val temp = files.upload(                                              // with an expiry; omit it and the file never expires
    FileSource.Bytes(jpegBytes), mimeType = "image/jpeg", filename = "cat.jpg",
    options = UploadOptions(expiresAfterSeconds = 3600),              // 3600..2592000 (1 hour to 30 days)
)

files.retrieve(uploaded.id)                              // fetch a single file
var page = files.list(after = null, limit = 100)         // cursor pagination; limit is 1..1000
while (page.hasMore) {
    page = files.list(after = page.lastId, limit = 100)
}
files.delete(uploaded.id)                                // delete
```

An out-of-range `UploadOptions.expiresAfterSeconds` or `list` `limit` fails fast at **call time**
(`IllegalArgumentException`) instead of burning a round trip. The return types are `DeepseekFile`
(`id` / `bytes` / `filename` / `expiresAt` and friends), `FileList` (`data` / `firstId` / `lastId` /
`hasMore`) and `FileDeletion`.

About `FileSource`: it implements `AutoCloseable`, so `use {}` guarantees release on exception and
cancellation. **Path sources only work on JVM / Android** — on Native / JS / Wasm `readBytes()` throws
`UnsupportedOperationException`, so use `FileSource.Bytes` there (those platforms need their own APIs to
read files anyway).

> **Official quotas**: ≤ 64 MiB per file, filename ≤ 512 characters, 25 GiB / 10000 files per user,
> expiry between 1 hour and 30 days.
>
> **Cancellation**: `cancelStream()` cancels chat streams only and **does not** affect file operations —
> cancel the coroutine that called them instead. `readBytes()` is synchronous and not interruptible, and
> each concurrent upload holds a full copy of the image on the heap, so bound the concurrency yourself
> (a `Semaphore`, for example).
>
> **Memory**: an inline base64 image stays in the conversation history, and every tool-loop iteration
> re-serializes that history — a 32 MiB image measured ~300 MiB of allocation for one request's JSON
> encoding. For large images, or images reused across several requests, use `files()` + `file_id`.

### Some Features

#### HttpClient Pool

By default every client shares `DeepseekHttpClientPool.Global`. Each instance can
use its own pool and tune timeouts or retries through the DSL:

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

> **IMPORTANT** When the pool is `DeepseekHttpClientPool.Global`, `pool { }` first
> copies it into an instance-level pool before applying changes, so the global
> shared configuration is never modified.

#### Custom API Provider (baseUrl)

By default every request (chat / models / balance / FIM) is sent to the official
`https://api.deepseek.com`. The `baseUrl` parameter points the client at any
OpenAI/DeepSeek-compatible API provider (proxy, gateway, or self-hosted endpoint);
both `Deepseek` and `StatelessDeepseek` support it:

```kotlin
// Constructor
val ds = Deepseek("<Your Key>", baseUrl = "https://my-provider.example.com/v1")

// DSL
val stateless = statelessDeepseek("<Your Key>") {
    baseUrl = "https://my-provider.example.com/v1"
    model { custom("my-model") } // Third-party providers usually need a custom model id
}
```

- `baseUrl` must be an absolute `http(s)` URL; invalid values throw
  `IllegalArgumentException` when the client is created
- Path prefixes are supported (e.g. `/v1` above); requests go to `{baseUrl}/chat/completions`
- A trailing `/` is stripped automatically, so `https://host/` equals `https://host`;
  userinfo (`https://user@host`), query (`?…`), and fragment (`#…`) are rejected
- `http://` transmits the API key in cleartext — use only for local proxies/testing
- Clients with different `baseUrl` values use separate pooled HTTP clients; the pool caches
  per baseUrl without eviction, so keep the baseUrl cardinality bounded (a few per provider/
  gateway) and call the pool's `close()` when appropriate to release resources
- `/models`, `/user/balance`, and FIM (`/beta/completions`) availability depends on the
  provider; unsupported endpoints return server-side errors

#### Tool Call Pipeline Design

For the tool call handling logic, this project adopts a pipeline design: each tool call is not executed directly, but first enters an interceptor chain composed of multiple phases. It passes through each phase before finally reaching the core executor. This allows cross-cutting concerns such as authentication, parameter validation, deserialization, retry, timeout, and logging to be plugged into the chain as "plugins" without invading the tool's business code.

A complete conversation (including tool call loops) roughly flows as follows:

```mermaid
flowchart TD
    A(["ds.chatStream(User Input)"]) --> B["Append User message to history"]
    B --> C{"Iterations remaining?<br/>iterations < maxToolIterations"}
    C -- No --> DONE["Emit aggregated Done<br/>accumulate token usage + finishReason"]
    DONE --> SAVE["Save Assistant reply"]
    SAVE --> FINISH(["Stream ends"])
    C -- Yes --> STREAM["Initiate streaming completion request<br/>Parse SSE chunks"]
    STREAM --> CHUNK["Emit ChatChunk events<br/>ContentDelta / ToolCallRequest / Done"]
    CHUNK --> HASTOOL{"Current response contains<br/>ToolCallRequest?"}
    HASTOOL -- No --> DONE
    HASTOOL -- Yes --> HANDLE["handleToolCalls<br/>Append assistant.tool_calls message"]
    HANDLE --> REG["ToolCallHost.execute<br/>Find executor by call.name"]
    REG --> PIPE["ToolCallPipeline<br/>Execute interceptor chain by phase"]
    PIPE --> RESULT["Get ToolResult<br/>Append role = tool message"]
    RESULT --> EMIT["Emit ToolResultData event"]
    EMIT --> WS{"All are server-side<br/>web_search?"}
    WS -- Yes --> DONE
    WS -- No --> C
```

Inside the pipeline, each phase can host any number of interceptors (components); interceptors within the same phase are executed in FIFO order (the earlier registered have lower priority).

The `ToolCallHost` holds all installed tool call functions. Each `Deepseek` instance is independent, and you can modify related content via `ChatConfig`.

<details>
<summary>Learn how to provide custom components for the ToolCallPipeline interceptor chain</summary>

> Several plugins are already built-in under `io.github.hatoyuze.deepseek.toolcall.pipeline.plugins`. You can install them using `PLUGIN.install(host)`.
>
> <details>
> <summary>Quick overview of built-in plugins</summary>
>
> | Plugin              | Phase    | Behavior                                                                                                                                                |
> |---------------------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
> | `RetryPlugin`       | `EXECUTE`| Catches `PipelineException` and retries with exponential backoff; other exceptions are converted to `PipelineException`; `PipelineException(isRetryable = false)` will not be retried |
> | `TimeoutPlugin`     | `EXECUTE`| Wraps the inner execution with `withTimeout`, throwing a `PipelineException` on timeout                                                                 |
> | `LoggingPlugin`     | All phases | Logs entry/exit and duration for each phase                                                                                                             |
> | `SerializationPlugin` | `TRANSFORM` | Automatically deserializes parameters for `TypedToolExecutor` and writes them to `ctx.typedParams`                                                      |
>
> > **These plugins are not installed by default**. You can install them by calling `retry()`, `timeout()`, `logging()` inside the `tools { }` DSL block (provided by `io.github.hatoyuze.deepseek.toolcall.dsl.ToolCallBuilderKt`).
>
> **Note: These plugins only affect retries at the toolcall level and do not affect the outer stream.**
> </details>
>
> Each pipeline plugin is invoked only at its specified phase, and there is a defined order of invocation.
>
> Phases are executed **sequentially** (the previous phase must finish before the next), while multiple interceptors within the same phase are **nested**:
>
> ```mermaid
> flowchart LR
>     subgraph PIPELINE["ToolCallPipeline Interceptor Chain"]
>         direction TB
>         V["VALIDATE<br/>Parameter Validation"] --> A["AUTHORIZE<br/>Authorization"]
>         A --> T["TRANSFORM<br/>Parameter Deserialization"]
>         T --> E["EXECUTE<br/>Core Execution"]
>         E --> P["POST_PROCESS<br/>Post-Processing"]
>         P --> R["ERROR<br/>Finalization Phase"]
>     end
>     CALL["ToolCallHost.execute(call)"] --> PIPELINE
>     PIPELINE --> RESULT["ToolResult"]
> ```
>
> ```mermaid
> sequenceDiagram
>     participant H as ToolCallHost
>     participant OUT as Outer Interceptor<br/>(registered later)
>     participant IN as Inner Interceptor<br/>(registered earlier)
>     participant CORE as Core Executor<br/>(innermost EXECUTE)
>     H->>OUT: execute(call, ctx)
>     OUT->>IN: ctx.proceed()
>     IN->>CORE: ctx.proceed()
>     CORE-->>IN: ToolResult
>     IN-->>OUT: return
>     OUT-->>H: ToolResult
> ```
>
> If you want to register a custom pipeline plugin, refer to the existing code for guidance.
>
> A simple authorization plugin:
>
> ```kotlin
> // import io.github.hatoyuze.deepseek.toolcall.pipeline.* / io.github.hatoyuze.deepseek.toolcall.dsl.toolHost
> class AuthPlugin(private val requiredPermission: String) {
> 
>     fun install(host: ToolCallHost) {
>         // Attach to AUTHORIZE phase: all tool calls pass through here first
>         host.intercept(ToolCallPhase.AUTHORIZE) { ctx ->
>             if (requiredPermission !in ctx.executionContext.permissions) {
>                 // Throw a business exception: the pipeline will convert it to ToolResult.error and return it to the model
>                 throw PipelineException(
>                     "Insufficient permission: need $requiredPermission",
>                     isRetryable = false, // Mark as non-retryable to avoid repeated execution by RetryPlugin
>                 )
>             }
>             ctx.proceed() // Permission passed, proceed to inner layers
>         }
>     }
> }
> 
> // Build the host first, then install the custom plugin
> val host = toolHost {
>     tool("get_balance") {
>         description = "Query account balance"
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
> Custom plugins must be installed after the host is built, as shown above.
>
> Note that **exceptions interrupt the entire call chain**. Exceptions thrown inside an interceptor are caught by the pipeline, which first executes the `ERROR` phase interceptors (where you can access the original exception via `ctx.error`), then converts it to `ToolResult.error`. `CancellationException` is propagated as-is and will not be swallowed or retried. If you need "failure retry" or "fallback on exception", wrap `ctx.proceed()` with `try/catch` like the `RetryPlugin` does.
>
> <details>
> <summary>Full example: custom plugin for logging tool execution time</summary>
>
> ```kotlin
> class TimingPlugin(private val onComplete: suspend (String, Long) -> Unit) {
> 
>     fun install(host: ToolCallHost) {
>         // Attach to EXECUTE phase, as the last EXECUTE interceptor installed, it is the outermost layer
>         host.intercept(ToolCallPhase.EXECUTE) { ctx ->
>             val start = System.currentTimeMillis()
>             try {
>                 ctx.proceed() // Enter inner layers (possibly retry / timeout / core executor)
>             } finally {
>                 onComplete(ctx.call.name, System.currentTimeMillis() - start)
>             }
>         }
>     }
> }
> 
> val host = toolHost {
>     tool("search") {
>         description = "Search the internet"
>         parameters { string("q") { required = true } }
>         execute { bag, _ -> """{"query":"${bag.getString("q")}"}""" }
>     }
>     retry(maxAttempts = 3) // Registered first → closer to the core executor
>     timeout(5_000)         // Registered later → wraps the whole retry loop
> }
> // Installed last → outermost EXECUTE interceptor, measures full execution time including retries and timeouts
> TimingPlugin { name, ms -> println("$name took ${ms}ms") }.install(host)
> ```
>
> </details>
>
</details>

#### Security

**The API key**
- The key is passed once at construction time and then sent as the `Authorization: Bearer <key>` request
  header — never in a URL, a query string, or a log line; exception messages strip the `Authorization`
  header before printing request headers (`redactedHeaders()`)
- The library does **not** store or manage your key: do not hardcode it in a repository, and keep it out of
  any string that ends up in a crash report. To rotate, create a new `Deepseek` instance (the key is
  per-instance and immutable after construction)
- When `baseUrl` points at a **third-party provider**, the key is handed to that provider; the library does
  not force an upgrade from `http://` to `https://`, so cleartext transport is the caller's decision

**`baseUrl` hardening**
- Only absolute `http(s)` URLs are accepted, and userinfo (`https://user:pass@host`, which can be used to
  spoof the target host), query strings and fragments are **rejected** — those components would silently
  misroute the `host + path` endpoint concatenation, so the client fails fast at construction
- Failure messages **do not echo** the original URL (the userinfo and query parts may carry credentials)

**Logging and `HttpHook`**
- `HttpHook` is the logging/debugging extension point, so the request body handed to it is **redacted**:
  inline base64 data URLs and long whitespace-free payloads become `<redacted N chars>`, and bodies over
  4096 characters are truncated with a `<truncated N chars>` marker. The request **structure**
  (model / messages / field names / content block types) is preserved
- A Files upload never exposes its multipart body to hooks — only a one-line summary (filename, byte count,
  `purpose`). Local paths and file contents are never written into it
- Strings that do reach logs (the filename) are validated against an allowlist at the API boundary, so
  control characters, quotes, backslashes and separators are rejected — no log forging, no leaking of the
  local directory layout

**Content and boundaries**
- Images are only accepted in `user` messages and only from `http(s)` / `data:` sources; anywhere else the
  library throws `IllegalArgumentException` **before** sending a request instead of leaving a guaranteed
  400 round trip behind
- Inline image size, external URL length, pagination `limit` and expiry ranges all fail fast locally; server
  quotas (≤ 64 MiB per image, 25 GiB per user, …) are decided by the server
- Request bodies are serialized with `explicitNulls = false`, so fields whose value is `null` are omitted
  entirely rather than sent as meaningless nulls

> Note what the library does **not** do: no content moderation, no image proxying, no SSRF protection —
> external images are downloaded by DeepSeek's servers, so never pass an internal address as `imageUrl`.

#### Performance characteristics

**Connection reuse**: by default every client shares `DeepseekHttpClientPool.Global`, so one `HttpClient`
(and one connection pool) is created per `baseUrl`. Replacing `pool.config` / `factory` closes and rebuilds
the cached clients — do not churn pool configuration at runtime.

**The `tools` array**: tool definitions are read once per stream (`getDefinitions()` is cached) and then
sent with **every request** — more tools means more input tokens and bandwidth, and that cost is the same
whether the host is shared or created per client.

**Inline images dominate memory** (measured): a 32 MiB image becomes ~42.7 MiB of base64, one request's JSON
encoding allocates ~300 MiB (about 390 MiB when the history contains CJK text, since those strings are
UTF-16), and Ktor needs its own copy of the body bytes. A tool-calling loop repeats all of it — five
iterations means hundreds of MiB of allocation and roughly five times the base64 upload traffic. Therefore:

- Large images, or images reused across iterations/requests → `files()` + `file_id` (stored once on the
  server, referenced by a short id in each request)
- When you must inline → **reuse the same `MessageContent` instance** across requests instead of calling
  `imageDataUrl(...)` in a loop
- The 32 MiB inline limit is the official maximum, not a recommendation; the comfortable range is far below it

**Who owns the thread**: `imageOf` / `FileSource.readBytes()` and the file read inside
`files().upload(...)` are **synchronous** and run on the calling thread. The library never switches
dispatchers and never reads a file twice for you (`FileSource.Bytes` does not copy the bytes; `Path` reads
once per call). Use `withContext(Dispatchers.IO)` on the UI thread, and bound concurrent uploads yourself
(each in-flight upload holds a full copy of the image on the heap).

**Cost under concurrency**: `StatelessDeepseek` supports concurrent streams while `Deepseek` is
single-session (a new stream cancels the previous one); `DeepseekFiles` is stateless and safe to share —
that is pinned by tests (20 concurrent uploads stay isolated, and 8 concurrent requests are measurably not
serialized behind an instance-level lock).

#### Platform differences

| Capability | JVM | Android | Native (iOS/macOS/Linux/Windows) | JS / Wasm |
|---|---|---|---|---|
| Default HTTP engine | CIO | OkHttp | CIO | js |
| Logging | kotlin-logging (SLF4J) | `android.util.Log` | standard output | browser / Node console |
| `FileSource.Path` | ✅ reads local files | ✅ process-accessible paths (`content://` needs your own readout) | ❌ throws `UnsupportedOperationException` | ❌ throws `UnsupportedOperationException` |
| Image resource helpers (`imageBytesOfResource`, …) | ✅ | ✅ | ❌ | ❌ |
| Main-thread blocking risk | yes (`readBytes()` is synchronous) | yes — ANR on the main thread | yes | single-threaded runtime, a long task blocks the whole app |
| Test coverage | `jvmTest` (incl. stress/concurrency) | `testDebugUnitTest` | `linuxX64Test` (runnable locally) | `jsNodeTest` / `wasmJsNodeTest` |

A few notes:

- **Android** needs the `INTERNET` permission; the default engine is OkHttp, and cleartext `http://` is
  blocked by the platform's network security config (use `https` for `baseUrl`). The Android target needs a
  local SDK — when it is missing, Gradle skips that target automatically without affecting the others
- **Native has no `FileSource.Path`**: not an oversight, but a Kotlin/Native metadata rule that rejects
  platform number types (`ftell` / `fread`) inside an `actual` declaration; working around it costs more
  than it is worth. Reading files on Native requires the platform API anyway (`NSData` etc.), so read the
  bytes and hand them to `FileSource.Bytes`
- **JS / Wasm have no local filesystem**, so use `FileSource.Bytes` there too; both are single-threaded, so
  encoding one large image occupies the event loop
- **Shared behaviour**: everything in `commonMain` (protocol, DSL, content blocks, redaction, fail-fast,
  history semantics) is identical on every platform — the differences are limited to file reading, log
  output and the HTTP engine

### License

Apache License 2.0. See [LICENSE](LICENSE).
