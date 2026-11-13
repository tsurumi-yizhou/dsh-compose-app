# DSH Compose

An unofficial Android app for DeepSeek Harness, built with Kotlin and Jetpack Compose. Run the agent locally with the bundled Linux runtime or connect to a remote host.

- Native chat interface with tool approvals and session history.
- Local runtime powered by PRoot.
- Extensible features through the Compose engine profile and plugins.

## Install

Download an APK from [GitHub Releases](https://github.com/tsurumi-yizhou/dsh-compose-app/releases). Requires Android 8.0 or later. Choose `arm64` for most phones or `x64` for x86_64 devices and emulators.

## Development

The host engine, bundles, bridge and SDK are maintained separately in [DSH Compose Host](https://github.com/tsurumi-yizhou/dsh-compose-host). This project is independently maintained and is not an official DeepSeek product.

See the [Android module README](app/README.md) for build commands and implementation details. GitHub Actions handles CI and signed releases.

## Related project

Forked from [dsh-go](https://github.com/ccch1mneyyy/dsh-go), an Android client for running DeepSeek Harness on your phone or accessing it on a computer.

## License

See [LICENSE.md](LICENSE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
