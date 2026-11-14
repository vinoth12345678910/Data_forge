# Release: 1.0.0-rc1 (release candidate)

Two packages are built from one command. Nothing here publishes anything: you upload the files yourself.

```bash
bash scripts/package.sh        # builds dist/ (not committed): the two .tar.gz files and SHA256SUMS
bash scripts/test-package.sh   # the gate test: jar builds, launcher runs, version prints, three JDBC drivers registered
```

| Package | Needs | Contents |
|---|---|---|
| `forge-1.0.0-rc1.tar.gz` | Java 21 on the PATH | `forge` (launcher), `lib/forge.jar`, `LICENSE`, `README.md` |
| `forge-1.0.0-rc1-macos-arm64.tar.gz` | Nothing (it carries its own Java 21 runtime, made with `jlink`) | `bin/forge`, `lib/forge.jar`, `runtime/`, `LICENSE`, `README.md` |

**Other operating systems and CPUs:** the jlink bundle is for macOS on Apple silicon only. For Linux, Windows or Intel macOS, build the bundle on that system with `bash scripts/package.sh` (it needs a JDK 21 with `jlink`). The jar package needs only Java 21 but its `forge` launcher is a bash script, so on Windows run `java -jar lib/forge.jar ...`. Neither was tried on another OS.

## Measured (Apple M4 MacBook Air, Java 21.0.12.1)

| What | Value |
|---|---|
| `lib/forge.jar` | 40,102,914 bytes |
| `forge-1.0.0-rc1.tar.gz` | 38,322,923 bytes |
| `forge-1.0.0-rc1-macos-arm64.tar.gz` | 70,916,854 bytes (the `runtime/` folder is 49 MB unpacked) |
| `forge --help` start time | 0.12 s (jar package), 0.14 s (jlink bundle); median of 5 runs |

Tried from a clean folder: `forge --version` prints `forge 1.0.0-rc1` from both packages; `forge mcp` answers `initialize` and lists its six tools; from the jlink bundle, `forge generate` and `forge verify` ran on the golden events schema in a SQLite database (`result: 0 FAIL, 0 ERROR`, exit 0). Not tried: Postgres or MySQL from the packages (the test suite covers both from the source tree), Windows, Linux.

## Checksums

Recorded on the final build of this round (see the last pull request); `dist/SHA256SUMS` is written by `package.sh`. A different build of the same source gives different checksums (the archives carry file times), so always compare against the `SHA256SUMS` that comes with the files you upload.

## Version

`1.0.0-rc1` is set in `build.gradle.kts` and `ManifestBuilder.TOOL_VERSION` (it appears in `forge --version`, every `--json` envelope as `tool_version`, manifests, and the MCP server info). No git tag and no GitHub release have been made.

## Web UI and terminal UI

They are not in these packages. Start them from the source tree: `./gradlew :server:run --args="0 --ui $PWD/ui/dist"` after `cd ui && npm ci && npm run build`, and `./gradlew :tui:run --console=plain`. The cli module cannot depend on them (they depend on it).
