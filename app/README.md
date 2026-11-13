# Android app

`:app` contains the Compose interface, navigation, page ViewModels, Android platform integration and application assembly. The Kotlin protocol client lives in `:data:protocol`; business repositories live in `:data`.

Build DSH Compose Host in its own checkout with `bash build.sh`, or download `host.tgz` from one of its releases. Point the build at the archive with `-PhostPackage=/absolute/path/to/host.tgz` (or the `DSH_HOST_PACKAGE` environment variable); there is no default location. Build the app with `./gradlew :app:assembleArm64Debug` and run unit tests with `./gradlew :app:testArm64DebugUnitTest`. Android packages this artifact with its guest setup scripts; it does not compile host sources.

Remote build delivery uses `deploy(path)` on the host. The paired client that sent the conversation message pulls the artifact and owns deployment: the Android app accepts Compose APKs and uses Android's installation confirmation. The host transports files unchanged and directories as tar archives (`kind` and `encoding` describe the transfer), without interpreting their contents. `/compose/deployments` provides polling, artifact downloads and receipts (`downloading`, `deploying`, `succeeded`, `failed`, `cancelled`, `unknown`).

Transfer copies are deleted on terminal receipts; unfinished jobs expire after 24 hours and terminal receipts are retained for another 24 hours. Host startup removes abandoned staging files, and the Android client removes download files after use or process restart. Original build outputs remain untouched.

## Releases

Publish a Host release and set this repository’s `HOST_RELEASE_TAG` Actions variable to that tag. CI and APK release jobs download `host.tgz` from `tsurumi-yizhou/dsh-compose-host`. For a private Host repository, set `HOST_DOWNLOAD_TOKEN` to a token with read access to its releases.

Configure the `release-signing` GitHub environment with `STORE_BASE64` (the Base64-encoded keystore), `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` secrets once. For each release, update `appVersion` and increase `appVersionCode` in `gradle.properties`, commit, and push a matching `v<appVersion>` tag. The Release workflow builds signed arm64 and x64 APKs and uploads them to a GitHub Release draft with generated notes. Review the draft and click **Publish release**. This workflow distributes full APKs only.

## File understanding and web research

In engine settings, open **File understanding & web research** (`compose-research`) and save a DeepSeek API key using the credential field (default reference: `DEEPSEEK_API_KEY`). Each of the five capabilities has an independent switch. The built-in presets expose `parse_document`, `ocr`, `web_search`, `web_fetch` and `deep_research`; the model chooses them from the user's request. Attach a file and ask a question, paste a page URL to read it, or ask for deep research. Expand a completed web tool to open its sources.

Document questions use text or rich-layout parsing; spreadsheets and text use `user_data`. OCR returns page-numbered recognition text. File tools accept this conversation's attachments and files inside its working directory and send the selected file to DeepSeek. Document uploads are deleted after the answer and expire after one hour if cleanup is interrupted. Cancelling stops local waiting; a service operation already accepted may still complete and be billed. Research uses a separate thinking-enabled request and returns its answer, sources and usage to the current agent; later turns can use the saved tool results or request further research. See the official [document parsing](https://api-docs.deepseek.com/zh-cn/guides/document_parsing/), [OCR](https://api-docs.deepseek.com/zh-cn/guides/ocr/) and [web tools](https://api-docs.deepseek.com/zh-cn/guides/web_tools/) guides.

## Android runtime

The app targets Android API 36. Local engine startup always uses PRoot and its loader installed in `nativeLibraryDir`; writable rootfs executables are loaded inside PRoot. The former direct glibc launch option is removed. Android 11+ shared-storage mounts require the user to grant All files access. Cloud backup and device transfer exclude application data, including the rootfs and credentials.
