Place bundled mediamp Windows runtime JARs here for local desktop builds.

Accepted input:
- `mediamp-mpv-runtime-<version>-windows-x64.jar`
- any additional `*.jar` runtime artifacts required by the Windows desktop player path

The desktop build copies all `*.jar` files from this directory into `composeApp/build/vendor-runtime/windows-x64/` through the `prepareWindowsRuntime` task.

These runtime JARs are consumed by:
- `:composeApp:run`
- `:composeApp:createReleaseDistributable`
- `:composeApp:packageReleaseDistributionForCurrentOS`

Override this directory at build time with either:
- `-Pnuvio.windowsRuntimeDir=C:\\path\\to\\runtime`
- `NUVIO_WINDOWS_RUNTIME_DIR=C:\\path\\to\\runtime`
