# DocsGPT Android

A minimal Android client for DocsGPT's streaming chat API. It's split into two Gradle modules:

- **`streaming-core`** — a pure Kotlin/JVM library with no Android dependency. It owns the
  actual "streaming repository": request/response models, an SSE-style event parser, and a
  `ChatStreamingRepository` that streams answers from `POST /api/answer/stream` as a
  `Flow<StreamEvent>`. Because it has no Android dependency it's fully unit-testable on a plain
  JVM (see `src/test`).
- **`app`** — a small Jetpack Compose app (`ChatViewModel` + `ChatScreen`) that consumes
  `streaming-core` to render a single-conversation chat UI, with a settings dialog for the API
  host and bearer token.

## Protocol

This mirrors what the web frontend does in `frontend/src/conversation/conversationHandlers.ts`
and what the backend implements in `application/api/answer/routes/{stream,base}.py`:

1. `POST {apiHost}/api/answer/stream` with a JSON body (`StreamAnswerRequest`): `question`,
   optional `conversation_id`, `prompt_id`, `chunks`, `retriever`, `api_key`, `agent_id`,
   `active_docs`, `isNoneDoc`, `index`, `save_conversation`, `model_id`, `attachments`, `history`.
   An `Authorization: Bearer <token>` header is added when a token is configured.
2. The response is `text/event-stream`, but it's a streamed POST body rather than a plain GET
   `EventSource`, so it's read line-by-line rather than through an SSE client library. Each
   event is framed as `data: <json>\n\n`.
3. Each JSON payload carries a `"type"` discriminator: `answer` (incremental text chunk),
   `source`, `tool_calls`, `thought`, `structured_answer`, `id` (conversation id, once),
   `error`, and `end` (terminates the stream). `StreamEventParser` decodes these into the
   `StreamEvent` sealed interface; `DefaultChatStreamingRepository` drives that parser off an
   OkHttp response body inside a `callbackFlow`, cancelling the underlying `Call` when the
   `Flow` collector is cancelled (e.g. the user taps "stop").

## Building

Open `extensions/android/` as a project root in Android Studio (Koala+ recommended) — it will
offer to generate the Gradle wrapper on first sync if one isn't present. Two things to know
before building:

- **This code was written and reviewed, but not compiled, in the sandbox that produced it.**
  That sandbox's egress policy blocks Maven Central, Google's Maven repository, and the Gradle
  Plugin Portal outright (verified via `403` responses), so no Gradle build — not even the
  dependency-free `streaming-core` module alone — could actually be resolved and run there. It
  was checked with a careful manual read-through instead of a green test run. Please run
  `./gradlew :streaming-core:test` (or open the module in an IDE) in an environment with normal
  internet access before relying on it; the tests in
  `streaming-core/src/test/kotlin/.../StreamEventParserTest.kt` and
  `DefaultChatStreamingRepositoryTest.kt` (using MockWebServer) are there specifically to catch
  regressions once that's possible.
- The app defaults to `https://docsapi.arc53.com`. Point it at your own DocsGPT deployment
  either at build time (`./gradlew assembleDebug -PdocsgptApiHost=https://your-instance.example.com`)
  or at runtime via the in-app settings dialog (gear icon), which also lets you set a bearer
  token for authenticated/agent API keys.

## Layout

```
extensions/android/
  streaming-core/                 # pure Kotlin JVM module
    src/main/kotlin/.../streaming/
      StreamModels.kt              # StreamAnswerRequest, SourceDoc, StreamEvent
      StreamEventParser.kt         # data: line framing -> StreamEvent decoding
      ChatStreamingRepository.kt   # interface
      DefaultChatStreamingRepository.kt  # OkHttp implementation
      DocsGptConfig.kt
    src/test/kotlin/.../streaming/
      StreamEventParserTest.kt
      DefaultChatStreamingRepositoryTest.kt   # MockWebServer-backed
  app/                             # Android application module
    src/main/java/com/docsgpt/android/
      DocsGptApplication.kt, MainActivity.kt
      di/AppContainer.kt           # manual DI, no Hilt/Dagger
      data/SettingsRepository.kt   # DataStore-backed api host + token
      ui/chat/                     # ChatUiState, ChatViewModel, ChatScreen
      ui/settings/SettingsDialog.kt
      ui/theme/Theme.kt
```
