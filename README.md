# S3 Artifacts Storage Selector (TeamCity server plugin)

Redirects artifact publishing to a pre-configured S3 artifacts storage for builds that run on
specific agents, without changing any project's default storage.

A server-side `BuildStartContextProcessor` inspects each starting build and, when the conditions
below are met, points that single build at an otherwise-inactive S3 storage. Nothing runs on the
agent.

## How it decides

For a starting build, in order:

1. **Explicit storage wins.** If the build's project resolves to an active artifacts storage — its
   own or inherited from an ancestor — the plugin leaves it alone. It only ever acts on builds that
   would otherwise use the built-in TeamCity server storage.
2. **Agent flag.** The assigned agent must carry `teamcity.artifacts.useS3Storage=true` as a
   configuration parameter (see below). Absent ⇒ `false`.
3. **Target storage id.** The project parameter `teamcity.artifacts.s3StorageId` must name a storage
   feature id. If it is absent, the build keeps the built-in storage (no warning — a flagged agent
   does not oblige every project to use S3).
4. **Validation.** The id must resolve for the build's project hierarchy. If it does not, the plugin
   fails safe to the built-in storage and logs a **warning** (stale or out-of-scope id).

When all hold, the plugin:

- adds the shared build parameter keyed `ArtifactStorageSettings.STORAGE_FEATURE_ID` (the agent
  artifact publisher reads this);
- sets the promotion attribute `BuildAttributes.STORAGE_SETTINGS_REFERENCE` (the server-side storage
  reference — defined as the same key as `STORAGE_FEATURE_ID`);
- persists the promotion;
- writes an INFO line to the **build log** and to the server log.

## Required setup (the load-bearing invariant)

The target S3 storage must exist but be **inactive**, and no ancestor project may have an active
storage. If any active storage resolves for the project, condition (1) short-circuits and the plugin
never fires.

1. Define the S3 artifacts storage on the project (or a shared parent) and **do not activate it**.
   The built-in server storage stays active.
2. Note the storage **feature id** (the `id` of the storage settings project feature).
3. Add a project configuration parameter `teamcity.artifacts.s3StorageId = <that feature id>`.
4. On each agent that should push to S3, add to `conf/buildAgent.properties`:

   ```properties
   teamcity.artifacts.useS3Storage=true
   ```

   Use a plain configuration parameter (no `env.` / `system.` prefix) so it stays agent metadata and
   never leaks into build processes. Changing it requires an agent restart; for cloud agents, bake
   it into the image.

## Build

```bash
./gradlew build          # compiles, runs unit tests, produces the plugin zip
./gradlew serverPlugin   # just the zip, in build/distributions/
```

- Targets the TeamCity API set by `teamcityVersion` in `gradle.properties` (default `2026.2`). The
  public repository (`https://download.jetbrains.com/teamcity-repository`) publishes `server-api`
  per *minor* release, not per patch, so there is no `2026.2.1` coordinate — `2026.2` is
  API-compatible with the `2026.2.1` runtime.
- **Classpath note:** the processor uses classes that live in TeamCity's server *implementation*
  rather than the slim `server-api` — `ArtifactsStorageSettingsManager`, `BuildPromotionEx`,
  `BuildAttributes`, `ArtifactStorageSettings`, `BuildLog`, `MessageAttrs`. These come from the
  `org.jetbrains.teamcity.internal:server` artifact, added on the `provided` classpath in
  `build.gradle.kts` (present on the running server at runtime, so not bundled in the plugin zip).

Deploy the zip from `build/distributions/` via **Administration → Plugins**, or drop it in the
server's `plugins/` directory and restart.

## Tests

`./gradlew test` runs the decision-table unit tests
(`SelectArtifactsStorageStartBuildProcessorTest`), covering all five branches plus the
no-build-type guard. The server-log INFO/WARN lines use a static logger and are confirmed in the
integration repro below rather than in the unit test.

## Integration repro (Depth A: selection only)

Verifies the plugin selects the right storage reference; it does not push bytes to S3 (that is
TeamCity's own, already-tested publishing path).

1. Stand up TeamCity **2026.2.1** (server + one agent) — e.g. with the official
   `jetbrains/teamcity-server` and `jetbrains/teamcity-agent` Docker images.
2. Define a dummy artifacts storage on a project and leave it **inactive**; note its feature id.
3. Set the project parameter `teamcity.artifacts.s3StorageId` to that id.
4. Set `teamcity.artifacts.useS3Storage=true` on the agent and restart it.
5. Run a build on that agent and confirm:
   - the build log shows the "Publishing artifacts to S3 storage '…'" line;
   - the promotion's resolved storage reference is the configured id;
   - a build on an **unflagged** agent (or with the flag off) keeps the built-in storage.
6. Misconfig check: set `teamcity.artifacts.s3StorageId` to a bogus id and confirm the build keeps
   the built-in storage and the server log shows the warning.

> Local caveat: any TeamCity build agent already installed on the host may auto-register against a
> test server on `localhost:8111` and hijack test builds. Stop that agent, or disable the stray
> agent via the REST API, before running builds.
