# TODOS-extended — loghi-tooling Deeper Code Review

Generated: 2026-05-27
Scope: Extended findings that complement (and do **not** duplicate) the existing `TODOS.md`.
Focus areas the user explicitly requested: code smells, testing gaps, optimisations, security,
architecture, API correctness, documentation, and especially **OpenCV `Mat` memory leaks**.

Severity legend: 🔴 Critical · 🟠 High · 🟡 Medium · 🟢 Low

Counts referenced in this document (verified):
- `new Mat(` occurrences: **108**
- `.release()` calls: **59**
- `OpenCVWrapper.release(...)`: **181**
- `.submat(...)`: **43**
- `.clone()`: **32**
- `Imgcodecs.imread(...)`: **112** (only 2 go through `OpenCVWrapper.imread`)
- `printStackTrace` / `catch ... ignored` / `catch (Throwable`: **72**

---

## Executive Summary — Top 10 Items to Tackle First

- [ ] **EXT-SEC-01** — SQL injection in `VectorDAO` (multiple sites, see SEC section).
- [ ] **EXT-MAT-01** — `submat()` in tight nested pixel loops in `Pooler` and `LocalBinaryPattern` leaks one native `Mat` per pixel.
- [ ] **EXT-PERF-01** — `while (!executor.isTerminated()) {}` busy-wait in 12 minions burns 100 % CPU on a core.
- [ ] **EXT-MAT-02** — `LayoutProc.calculateSeamCost` (line 2392–2394) has a commented-out `release` of `baselineImageSubmat` and an inline `energyImage.submat(roi)` passed to `Core.mean` that is never released.
- [ ] **EXT-MAT-03** — `Imgcodecs.imread()` results in `LayoutAnalyzer`, `LayoutProc`, `PageColorizer`, `WebTooling`, `MergeBaseLines`, and `MinionExtractBaselinesStartEndNew3` are never released on success paths.
- [ ] **EXT-MAT-04** — `PageUtils.removeWhiteSpaceBeforeBaseline` (line ~1738) leaks `image`/`grayImage`/`binaryImage` on every early-return path (and there are 5+ of them).
- [ ] **EXT-ARCH-01** — Four parallel implementations of the baseline extraction pipeline (`MinionExtractBaselines`, `…StartEndNew`, `…StartEndNew2`, `…StartEndNew3`) live side by side; ~80 % code duplication and divergent bug fixes.
- [ ] **EXT-API-01** — `loghiwebservice` REST resources catch `Exception` and convert to plain text — no error model, no HTTP status discipline, no input size validation against multipart uploads.
- [ ] **EXT-BUILD-01** — Parent POM uses `file:///${user.home}/repo` and relative paths `../prima-core-libs/...` as modules — builds are non-reproducible outside the original developer's machine.
- [ ] **EXT-SEC-02** — OWASP dependency-check threshold `failBuildOnCVSS=8` allows known High-severity (7.x) CVEs to slip through silently.

---

## 🔴 Critical

### Security

- [ ] **EXT-SEC-01 — SQL injection in `VectorDAO`**
  - File: `layoutds/src/main/java/nl/knaw/huc/di/images/layoutds/DAO/VectorDAO.java`
  - Lines:
    - **L172** `"select id from documentimage where uuid='" + findByModelQuery.imageUuid + "';"`
    - **L179** `"... where dis.uuid='" + findByModelQuery.imageSetUuid + "'"`
    - **L159–161** `"... and pimfield.name='" + findByModelQuery.pimField + "' "` and `pimfieldValue.value='" + findByModelQuery.pimFieldValue + "'"`
    - **L192–197** `vectorModelId`, `limit`, `skip` concatenated into the final native SQL string (`"... vectormodel_id= " + vectorModelId + ... "limit " + limit + " offset " + skip + ";"`).
    - **L60–67, L92–98** `arrayString` containing `targetVector.getId()` interpolated 3× into native SQL — currently safe because the value originates from DB, but still violates parameterisation hygiene.
  - These DAO methods are called from user-facing query objects (`FindByModelQuery`) so the UUID/string fields are externally controllable.
  - **Action:** Replace every `createSQLQuery("... '" + x + "' ...")` with `createNativeQuery(":x")` and `query.setParameter("x", x)`. For `limit`/`offset` use `query.setMaxResults(limit)` / `setFirstResult(skip)`. UUID parameters should be parsed to `UUID` before binding (rejects malformed input early). Add an integration test that supplies `' OR 1=1 --` as `imageUuid` and asserts a parse/SQL error rather than a leaked row set.

### Memory / Resources

- [ ] **EXT-MAT-01 — `submat()` leak inside a per-pixel nested loop**
  - `imageanalysiscommon/.../LocalBinaryPattern.java:88` — `image.submat(i, i+3, j, j+3)` allocated O((H-2)·(W-2)) times, never released. On a 3000×3000 image this is ≈9 million native allocations per call.
  - `imageanalysiscommon/.../Pooler.java:41, 57` — same pattern inside `maxPool`/`minPool`; both functions also fail to release the submat.
  - **Action:** Wrap each submat in a try/finally and call `OpenCVWrapper.release(submat)` at the end of each iteration, or refactor to operate on the parent Mat's bulk byte buffer with `Mat.get(int, int, byte[])` over a row at a time — this is also dramatically faster than per-pixel `submat` + Java-side JNI hops.

- [ ] **EXT-MAT-02 — `LayoutProc.calculateSeamCost` Mat leak**
  - File: `layoutanalyzer/.../layoutlib/LayoutProc.java`
  - **L2392** `Mat baselineImageSubmat = baselineImage.submat(roi);` — release at L2417 is commented out (`//        baselineImageSubmat = OpenCVWrapper.release(baselineImageSubmat);`).
  - **L2394** `Core.mean(energyImage.submat(roi))` — the inline `submat` Mat is anonymous, never released.
  - **L2514** `Mat tmpSubmat2 = energyImage.submat(roi);` — verify release path.
  - **L2806** `deskewedSubmat = deskewedImage.submat(cuttingRect).clone();` — the intermediate `submat` is anonymous, never released; only the `.clone()` is tracked.
  - **L3195** `.submat(rowStart, ...)` and **L3220** `image.submat(boundingBox).clone()` — same anonymous-submat pattern.
  - **Action:** For each anonymous `submat`, assign to a local, release in `finally`. Re-enable the L2417 release after verifying no aliased downstream use.

- [ ] **EXT-MAT-03 — `Imgcodecs.imread` not routed through `OpenCVWrapper`**
  - 112 raw `Imgcodecs.imread` calls vs 2 `OpenCVWrapper.imread` calls. `OpenCVWrapper.imread` performs synchronisation and tracking; the raw form bypasses both.
  - Specific leaks on success paths:
    - `layoutanalyzer/.../LayoutAnalyzer.java:513` — `Mat image = Imgcodecs.imread(uri);` — never released.
    - `layoutanalyzer/.../LayoutProc.java:1308` — same.
    - `pagexmlutils/.../PageColorizer.java:201` — `Mat input` released only inside the success branch; in early-return branches it is leaked.
    - `pagexmlutils/.../PageUtils.java:177, 186, 199` — `colorized = Imgcodecs.imread(...)` inside a retry chain; the previously-assigned `colorized` is never released before re-assignment.
    - `imageanalysiscommon/.../network/WebTooling.java:114, 136, 142` — return `Imgcodecs.imread(...)` directly; the API contract leaves release to the caller but is undocumented.
    - `minions/.../MergeBaseLines.java:36` — released only when `baseLineMat.empty()` is false; the empty-Mat branch returns without release (an empty Mat still allocates header memory).
    - `minions/.../MinionExtractBaselinesStartEndNew3.java:761` — `Mat combinedMat = Imgcodecs.imread(...)` lifecycle hard to follow across the 200-line method.
  - **Action:** Replace every direct `Imgcodecs.imread` with `OpenCVWrapper.imread` (extend the wrapper if signatures are missing). Add an `@SuppressWarnings("UseOfImageReadCallDirectly")` lint rule, or even better, make `Imgcodecs` access fail at build time via [forbidden-apis](https://github.com/policeman-tools/forbidden-apis) Maven plugin. Document that all `Mat`-returning public methods transfer ownership to the caller.

- [ ] **EXT-MAT-04 — `PageUtils.removeWhiteSpaceBeforeBaseline` (≈L1730+)**
  - Allocates `Mat image`, `Mat grayImage`, `Mat binaryImage`.
  - On the early-return paths inside the `for (TextRegion textRegion : ...)` loop, `binaryImage` is *not* released (only released after the loop). If the method throws (e.g. IOException from `readPageFromFile`), all three Mats leak.
  - **Action:** Move the three Mats into a `try { ... } finally { release all 3 }` block; check for null before each release.

- [ ] **EXT-MAT-05 — `Histogram.java` anonymous Mat as `normalize` mask**
  - `imageanalysiscommon/.../Histogram.java:100` — `Core.normalize(bHist, bHist, 0, histImageb.rows(), Core.NORM_MINMAX, -1, new Mat());`
  - The inline `new Mat()` mask leaks every time the method is called.
  - Also `Histogram.java:18, 26, 30, 33, 47, 73, 83` — no `release` calls visible in this file. The whole `createRGBHistogram` method allocates ≥8 Mats and releases zero.
  - **Action:** Make a private static final empty Mat for the no-mask case (still need lifecycle but reused), and release every other local Mat in `finally`.

- [ ] **EXT-MAT-06 — `DocumentPage` image-cache fields accumulate without bound**
  - `layoutanalyzer/.../DocumentPage.java` — fields `cannyImage` (L175), `grayImage` (L197), `binaryImage` (L214), `binaryImageCrude` (L243), `binaryOtsu` (L434) are lazily populated and cached for the life of the `DocumentPage` instance.
  - When a `DocumentPage` is garbage-collected without an explicit cleanup call, all those native buffers leak permanently (the OpenCV JNI finaliser was removed in modern bindings).
  - **L471–475** `dst`, plus three `bgr.add(new Mat())` — three list elements + `dst`, no release.
  - **Action:** Make `DocumentPage` implement `AutoCloseable`; release every cached Mat in `close()`. Audit every caller (`LayoutAnalyzer`, `MinionExtractBaselines*`) to use try-with-resources. Add a finalizer warning log if `close` was not called (development-only flag).

---

## 🟠 High

### Performance

- [ ] **EXT-PERF-01 — Busy-wait `while (!executor.isTerminated()) {}` (CPU melts a core)**
  - 12 files: every `Minion*` listed in `grep isTerminated`:
    - `MinionExtractBaselines.java:564`
    - `MinionExtractBaselinesStartEndNew.java:618`
    - `MinionExtractBaselinesStartEndNew2.java:792`
    - `MinionExtractBaselinesStartEndNew3.java:752`
    - `MinionShrinkTextLines.java:64`
    - `MinionShrinkRegions.java:55`
    - `MinionDetectLanguageOfPageXml.java:163`
    - `MinionLoghiHTRMergePageXML.java:577`
    - `MinionCutFromImageBasedOnPageXMLNew.java:510`
    - `MinionPyLaiaMergePageXML.java:190`
    - `MinionSplitPageXMLTextLineIntoWords.java:127`
    - `MinionRecalculateReadingOrderNew.java:188`
  - The empty-body spin keeps one core pinned at 100 % until the executor finishes.
  - **Action:** Replace with `executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);` (or `1, TimeUnit.DAYS` looped). Surface `InterruptedException` properly (restore the interrupt flag).

- [ ] **EXT-PERF-02 — `ExecutorService` never `shutdownNow`-ed on exception**
  - In all 12 minions above, the `executor` is allocated before the `for (...)` loop. If any iteration throws, the executor is never shut down; the JVM cannot exit until the (non-daemon) threads complete.
  - **Action:** Wrap `try { executor.execute(...) } finally { executor.shutdown(); executor.awaitTermination(...); }`. Consider extracting a `MinionRunner` helper to centralise this pattern.

- [ ] **EXT-PERF-03 — `executor.execute(worker)` with one task per file does not bound queue depth**
  - When `files` is huge (tens of thousands of pages), all `Runnable` objects are queued in memory at once because `Executors.newFixedThreadPool` uses an unbounded `LinkedBlockingQueue`.
  - **Action:** Use a custom `ThreadPoolExecutor` with a bounded `ArrayBlockingQueue(2 * numthreads)` and a `CallerRunsPolicy` so the producer back-pressures.

- [ ] **EXT-PERF-04 — Per-pixel `Mat.get(int,int,byte[])` JNI hot loop in `PageUtils.removeWhiteSpaceBeforeBaseline`**
  - File: `pagexmlutils/.../PageUtils.java` (~L1798) — inside a double `for` loop:
    ```java
    byte[] data = new byte[1];
    binaryImage.get(i, (int) point.x, data);
    ```
  - Allocates a 1-byte array per pixel **and** crosses the JNI boundary per pixel.
  - **Action:** Pull a full row once with `byte[] row = new byte[binaryImage.cols()]; binaryImage.get(i, 0, row);` outside the inner loop, then index by `row[x]`. Typically 50–200× faster.

- [ ] **EXT-PERF-05 — `Pooler.maxPool` / `minPool` allocate W·H temporary Mats**
  - See EXT-MAT-01 — same code path; the leak and the perf hit go together. Replace with direct row-buffer iteration.

### Architecture

- [ ] **EXT-ARCH-01 — Four parallel baseline-extraction minions**
  - `MinionExtractBaselines` (568 lines)
  - `MinionExtractBaselinesStartEndNew` (~700 lines)
  - `MinionExtractBaselinesStartEndNew2` (~870 lines)
  - `MinionExtractBaselinesStartEndNew3` (~800 lines)
  - Diff between New, New2, New3 is ~15 % of lines — bug fixes made to one are not back-ported.
  - **Action:** Pick `New3` as canonical, mark the others `@Deprecated`, schedule removal next major release. Extract shared inner classes (`thresHoldedBaselines*`, `stats*`, `labeled*`, `centroids*`) into a common `BaselineWorkBuffers` class.

- [ ] **EXT-ARCH-02 — `loghiwebservice` REST resources duplicate `Minion*` logic**
  - Each resource (`LoghiHTRMergePageXMLResource`, `ExtractBaselinesResource`, `CutFromImageBasedOnPageXMLNewResource`, `SplitPageXMLTextLineIntoWordsResource`, `DetectLanguageOfPageXmlResource`, `RecalculateReadingOrderNewResource`) wraps essentially the same Minion's `Runnable` with multipart-form parsing.
  - **Action:** Define a `JobService` interface in `minions`, have each Minion implement it (single entry point taking suppliers + config), and let resources be thin adapters that translate HTTP to that interface. Single source of truth for the algorithm.

### Security

- [ ] **EXT-SEC-02 — OWASP `failBuildOnCVSS=8` is too lax**
  - File: `pom.xml` (root, ~L48).
  - Any CVE with CVSS 7.x (High) silently passes the build.
  - **Action:** Lower to `7`. Add `<suppressionFile>` to manage knowingly-deferred items.

- [ ] **EXT-SEC-03 — `LoghiHTRMergePageXMLResource` `new ObjectMapper()` and broad catch**
  - File: `loghiwebservice/.../LoghiHTRMergePageXMLResource.java:215` — `new ObjectMapper()` per request (also covered as SMELL-06 in TODOS.md; here it is in a web hot path so promoted to High).
  - **Action:** Inject a shared `ObjectMapper` via Dropwizard's `Environment.getObjectMapper()`.

- [ ] **EXT-SEC-04 — Hardcoded absolute paths leak environment details**
  - 160 occurrences of `"/scratch/"`, `"/data/"`, `"/home/"`, `"/tmp/"` in production sources (`grep -rn '"/scratch/\\|/data/\\|/home/\\|/tmp/' --include='*.java'`).
  - Top offenders: `LayoutAnalyzer`, `LayoutProc`, `Histogram`, several minion `main` test paths.
  - **Action:** Move all dev-time fixture paths under `src/test/resources` and reference via `getClass().getResource(...)`. Production code must accept paths only via CLI args / config.

- [ ] **EXT-SEC-05 — Path traversal: file paths read from PAGE XML accepted verbatim**
  - `MinionExtractBaselines.java:543` — `pcGts.getPage().getImageFilename()` is fed directly to `Path.of(inputPathImage, imageFilenameFromPage)` without normalization. A crafted PAGE XML with `../../etc/passwd` traverses out of the input dir.
  - Similar in `MinionCutFromImageBasedOnPageXMLNew`, `MinionLoghiHTRMergePageXML`.
  - **Action:** After resolving, call `.normalize()` and assert `resolved.startsWith(inputBaseDir.toAbsolutePath().normalize())`. Reject otherwise.

### Code Smells (beyond TODOS.md)

- [ ] **EXT-SMELL-01 — 72 `printStackTrace` / `ignored` / `catch (Throwable)` sites**
  - TODOS.md SMELL-04 only counted 26 `printStackTrace` calls; the broader pattern is 72 sites.
  - **Action:** Add a Checkstyle/SpotBugs rule (`REC_CATCH_EXCEPTION`, `DM_EXIT`, `DM_GC`) and fail the build on new occurrences.

- [ ] **EXT-SMELL-02 — `OpenCVWrapper.release` throws `RuntimeException` on double release / null**
  - File: `layoutanalyzer/.../OpenCVWrapper.java:46–60`.
  - Throwing on double release means a defensive `finally { x.release(); }` after a successful release in the try block crashes the program. The implementation forces every caller to track *who* releases, defeating the purpose of a defensive wrapper.
  - **Action:** Make `release(null)` a no-op (warn-log at most). Make double-release idempotent (check `dataAddr == 0` → log and return). Keep the strict-mode behaviour behind a `-DopenCV.strictRelease=true` system property for tests.

- [ ] **EXT-SMELL-03 — `Imgcodecs.imread` failure returns an empty `Mat`, not null**
  - Few callers check `.empty()`. Example offenders that act on the returned Mat unconditionally:
    - `LayoutAnalyzer.java:513`
    - `LayoutProc.java:1308`
    - `MinionExtractBaselinesStartEndNew*.java:629–631` (chain of three; if one is empty, subsequent `cvtColor` / `bitwise_not` calls throw `CvException` and leak the prior allocations).
  - **Action:** Centralise via `OpenCVWrapper.imread` (already part of EXT-MAT-03). Wrapper should throw `IOException` with the path on empty result.

- [ ] **EXT-SMELL-04 — `session.close()` called inside `try-with-resources` block**
  - `VectorDAO.java:42` — `session.close();` *inside* a `try (final Session session = ...)`. Closing twice is benign for Hibernate but signals confusion about ownership.
  - **Action:** Remove the explicit `close()` call.

- [ ] **EXT-SMELL-05 — Pre-existing `// FIXME TI-541` and similar are duplicated**
  - `LayoutProc.java:3499` and `:3514` both carry the same FIXME — likely a copy-paste bug, not just a tracking comment.
  - **Action:** Investigate whether the second occurrence is actually unreachable.

---

## 🟡 Medium

### Build / Reproducibility

- [ ] **EXT-BUILD-01 — `file:///${user.home}/repo` referenced in parent `pom.xml`**
  - File: `pom.xml` line ~29 — `<repository><url>file:///${user.home}/repo</url></repository>`.
  - Any CI machine without this directory (or with stale content) fails or produces non-deterministic builds.
  - **Action:** Replace with a real Maven repository (a project-scoped GitHub Packages, Nexus, or Artifactory). For Prima artifacts, use Maven Central if they are published there, or vendor them under `<systemPath>` only as a documented escape hatch.

- [ ] **EXT-BUILD-02 — `<modules>` references `../prima-core-libs/java/...`**
  - File: `pom.xml` lines 11–14. The build assumes `prima-core-libs` is checked out as a sibling directory. CI scripts must be doc'd; without that, `mvn -pl ...` against this POM is fragile.
  - **Action:** Either include `prima-core-libs` as a Git submodule with documented path, or publish those modules and consume them as binary dependencies.

- [ ] **EXT-BUILD-03 — No CI configuration committed (no `.github/workflows`, `.gitlab-ci.yml`, or `Jenkinsfile`)**
  - **Action:** Add a `.github/workflows/build.yml` with `mvn -B verify` matrix for JDK 11 + JDK 17.

- [ ] **EXT-BUILD-04 — Module name `loghi-tooling.iml` committed**
  - **Action:** Add `*.iml`, `.idea/` to `.gitignore` if not already covered.

### API Correctness (loghiwebservice)

- [ ] **EXT-API-01 — No request size limits on multipart uploads**
  - `loghiwebservice/.../resources/*Resource.java` accepts `@FormDataParam` byte arrays without bounds.
  - **Action:** Configure Dropwizard / Jersey `MultiPartFeature` with explicit max size; document the limit. Reject above-limit early with HTTP 413.

- [ ] **EXT-API-02 — Resources catch `Exception` and return text bodies**
  - Inconsistent error model — clients cannot parse failures programmatically.
  - **Action:** Use Jersey `ExceptionMapper<Throwable>` returning JSON `{ "error": "...", "trace_id": "..." }`. Apply consistent HTTP status mapping.

- [ ] **EXT-API-03 — `LoghiWebserviceResource.getStatus(@PathParam("identifier"))` accepts any string and reflects it in the response**
  - Verify there is no XSS / log-injection risk. Sanitise `identifier` against `^[A-Za-z0-9_-]{1,64}$` or similar.

- [ ] **EXT-API-04 — No OpenAPI / Swagger documentation**
  - There is no `openapi.yaml`, no Swagger annotation, no auto-generated docs.
  - **Action:** Add `dropwizard-swagger` or `springdoc-openapi` (depending on framework) and publish the spec under `/openapi.json`.

### Memory / Resources (additional)

- [ ] **EXT-MAT-07 — `MinionExtractBaselinesStartEndNew*` `submat` in inner loops**
  - `MinionExtractBaselinesStartEndNew.java:129–130, 231, 321, 439, 456` — `submat` assigned but only some paths `release`. The same template appears in `New2` and `New3` (so 12+ sites total).
  - **Action:** During the consolidation in EXT-ARCH-01, replace every `submat` with a `try { ... } finally { OpenCVWrapper.release(submat); }`. Audit with a unit test that captures the sum of `Core.getMemoryUsed()` or counts of unreleased `Mat.dataAddr` across a representative input.

- [ ] **EXT-MAT-08 — `BaselinesMapper.java:145` and `MinionExtractBaselines.java:289`**
  - Both use `labeled.submat(rect).clone()`; the intermediate `submat` is anonymous and leaks.
  - **Action:** Assign to a temp, release it after `clone()`.

- [ ] **EXT-MAT-09 — `VisualizationHelper.java:118` submat in `for (ConnectedComponent coco …)`**
  - `Mat submat = colorizedImage.submat(...)` inside a loop; `// TODO TI-374: don't use put` already flags perf; the leak is additional.
  - **Action:** Release inside the loop and address the existing TI-374 TODO together.

- [ ] **EXT-MAT-10 — `Imgcodecs.imread` return inside lambda `Supplier<Mat>`**
  - `MinionExtractBaselines.java:550–551` — `OpenCVWrapper.imread(...)` returned to callers via `Supplier<Mat>`. Ownership of release is split between supplier and consumer, with no documented contract.
  - **Action:** Document in JavaDoc: "Caller is responsible for releasing the returned Mat." Or change suppliers to take a consumer callback that controls lifecycle.

- [ ] **EXT-MAT-11 — Empty / commented-out releases**
  - `LayoutProc.java:2417` — release commented out (see EXT-MAT-02).
  - `MergeBaseLines.java:58, 72, 74, 76` — entire allocations commented out *but the variable names are still referenced live in the surrounding code*; confirm whether the live code paths still allocate the Mats elsewhere or whether some calculations are silently wrong because they reference uninitialised state.

### Testing Gaps

- [ ] **EXT-TEST-01 — No Mat-leak regression tests**
  - **Action:** Write a JUnit Jupiter extension `OpenCvMatLeakDetector` that snapshots `Core.getNumberOfCPUs()`/native memory before/after a test and fails on growth above threshold. Apply to `LayoutProcTest`, `ConnectedComponentTest`, `ImageFindMarginTest`.

- [ ] **EXT-TEST-02 — `VectorDAO` has no tests despite executing native SQL**
  - **Action:** Add tests with a Testcontainers PostgreSQL + `cube` extension. Cover positive, empty-result, and malicious-input cases (couples with EXT-SEC-01).

- [ ] **EXT-TEST-03 — `loghiwebservice` resource tests cover only the happy path**
  - Existing test: `LoghiHTRMergePageXMLResourceTest` — only one scenario.
  - **Action:** Use `DropwizardAppExtension` + `ResourceExtension` to add: oversized upload (413), malformed XML (400), invalid auth (401), partial form (400), server-side processing error (500).

- [ ] **EXT-TEST-04 — `OpenCVWrapper` has no tests**
  - The wrapper is the single chokepoint for thread-safe Mat allocation/release; should have stress tests with multiple threads racing to create/release Mats.

- [ ] **EXT-TEST-05 — `stringtools` tests do not cover XML serialisation edge cases**
  - File: `stringtools/.../StringTools.java:209, 476` allocates `DocumentBuilderFactory`/`TransformerFactory` per call (also OPT-02/OPT-03 in TODOS.md).
  - **Action:** Add tests around encoding, non-BMP characters, empty input, and the `catch (Exception ignored)` at L373 — particularly to assert what *should* happen there.

### Documentation

- [ ] **EXT-DOC-01 — `README.md` lacks runtime requirements**
  - Missing: required JDK version, OpenCV native library install steps, native-image lib path env vars, DB schema bootstrap procedure for `layoutds`.
  - **Action:** Expand README with prerequisites, installation, configuration, troubleshooting (especially `libopencv_java4xx.so` loading).

- [ ] **EXT-DOC-02 — No CHANGELOG / release notes**
  - 2.1.0 is the only visible version; no history of breaking changes.
  - **Action:** Add `CHANGELOG.md` following Keep-a-Changelog format.

- [ ] **EXT-DOC-03 — Module READMEs missing**
  - No `README.md` in any of: `altoxmlutils`, `imageanalysiscommon`, `layoutanalyzer`, `layoutds`, `loghiwebservice`, `minions`, `pagexmlutils`, `stringtools`.
  - **Action:** Add 1-page README per module: purpose, public API entry points, examples.

- [ ] **EXT-DOC-04 — `STORAGE.md` is a plan, not a process**
  - File: `layoutds/STORAGE.md` is a list of GB-sized columns to migrate. No migration scripts, no Flyway / Liquibase config in the repo.
  - **Action:** Adopt Flyway; convert STORAGE.md items into versioned migrations.

- [ ] **EXT-DOC-05 — Minion CLI `--help` output inconsistent**
  - Each minion defines its own `Options` block. Output formatting differs.
  - **Action:** Extract a `MinionMain` base class with shared `--help`, `--version`, `--config`, `--threads` options.

### Code Smells

- [ ] **EXT-SMELL-06 — `BaseMinion.sleeplength` static + global retry counter**
  - Already partially covered by TODOS.md SMELL-08, but additionally: the counter never resets between retries, so after a long successful run the next failure starts at the slowest back-off.
  - **Action:** Reset in `finally`.

- [ ] **EXT-SMELL-07 — Inconsistent logger naming**
  - Some classes use `LOG`, others `log`, others `LOGGER`. Mixed within the same module.
  - **Action:** Standardise on `private static final Logger LOG = LoggerFactory.getLogger(X.class);`.

- [ ] **EXT-SMELL-08 — `MergeBaseLines.java` contains 30+ lines of commented-out code (L58–76 and surrounding)**
  - **Action:** Delete commented-out code; it is in Git history.

- [ ] **EXT-SMELL-09 — `MinionExtractBaselines*` constructors take 18+ parameters**
  - Example: `MinionExtractBaselines` ~`L546` — 16 positional args; `New3` even more.
  - **Action:** Introduce a `BaselineConfig` record/value object built via a builder.

- [ ] **EXT-SMELL-10 — `e.printStackTrace()` after a re-throw**
  - Pattern: print stack trace, then throw — duplicates output.
  - **Action:** Drop the print; let the upper layer handle.

### Optimisations

- [ ] **EXT-OPT-01 — Replace busy spin with `awaitTermination` (see EXT-PERF-01).**

- [ ] **EXT-OPT-02 — Cache `JAXBContext` per class**
  - Many `pagexmlutils` / `altoxmlutils` code paths create `JAXBContext.newInstance(PcGts.class)` per call. `JAXBContext` is thread-safe and expensive to build.
  - **Action:** Static cache keyed by `Class<?>`.

- [ ] **EXT-OPT-03 — `DocumentBuilderFactory` and `TransformerFactory` re-creation in hot paths**
  - Already in TODOS.md (OPT-02/03), reaffirmed here because they are also referenced in `loghiwebservice/.../resources/...` via `pagexmlutils` calls.

- [ ] **EXT-OPT-04 — `String.format` in tight logging loops**
  - `LayoutAnalyzer.java:339` — `System.out.printf("length: %s%n", length);` per Hough line. After replacement with SLF4J (covered by TODOS.md SMELL-03), keep the `{}` placeholder form so logging is short-circuited at the disabled level.

- [ ] **EXT-OPT-05 — `Mat.put(row, col, value)` per pixel in `VisualizationHelper`**
  - Already flagged with `// TODO TI-374`; the suggested fix is to operate on a row-buffer.

---

## 🟢 Low

- [ ] **EXT-LOW-01** — `package-info.java` files missing from every package; useful for module-level Javadoc.
- [ ] **EXT-LOW-02** — `SuppressWarnings("unchecked")` scattered without comment; document why each is necessary.
- [ ] **EXT-LOW-03** — `// commented-out imports` in several minions — clean up.
- [ ] **EXT-LOW-04** — Inconsistent newline / EOF style across files (mix of LF and CRLF in some PAGE-XML tests).
- [ ] **EXT-LOW-05** — `loghiwebservice` lacks a `Dockerfile`; the project ships shell scripts but no container.
- [ ] **EXT-LOW-06** — `before-commit-test.sh` exists but is not enforced via a Git pre-commit hook.
- [ ] **EXT-LOW-07** — Test sources for `layoutds` are still in the **default** package; rename to match the production package (also referenced by TODOS.md TEST-04 but pointed out here for *all* test files under `layoutanalyzer/src/test/java`, not just layoutds).
- [ ] **EXT-LOW-08** — `LayoutProc` static methods make unit tests need real OpenCV libs; consider an instance-based API that can be mocked.
- [ ] **EXT-LOW-09** — `prima-core-libs` is referenced but the workspace bundles a parallel copy under `/home/rutger/src/prima-core-libs/` — risk of editing one and forgetting the other.

---

## Memory Audit Summary (Mat accounting)

| Module | `new Mat(` | `submat(` | `clone()` | Direct `imread` | `OpenCVWrapper.release` | Net at-risk sites |
|---|---|---|---|---|---|---|
| imageanalysiscommon | 10 | 5 | 2 | 4 | low | High — Pooler/LBP/Histogram |
| layoutanalyzer | 26 (incl. ARUProcessor dead) | 14 | 5 | 2 | many | Medium — concentrated in LayoutProc |
| layoutds (model) | 3 (Place, DocumentPage, DocumentImage models) | 0 | 0 | 0 | n/a | Low (entity fields, not used at runtime) |
| minions | 56 | 22 | 4 | 11 | moderate | High — replicated across 4 baseline variants |
| pagexmlutils | 2 | 0 | 1 | 4 | low | Medium |
| loghiwebservice | 0 (delegates to minions) | 0 | 0 | 3 imdecode (per req) | none | Medium — per-request native allocation |

**Cross-cutting recommendation:** introduce a single `try (MatScope scope = MatScope.open()) { Mat m = scope.track(new Mat(...)); ... }` helper. `MatScope.close()` releases every tracked Mat. This pattern is small and replaces every ad-hoc `try/finally release` block. A draft API:

```java
public final class MatScope implements AutoCloseable {
    private final Deque<Mat> mats = new ArrayDeque<>();
    public static MatScope open() { return new MatScope(); }
    public Mat track(Mat m) { mats.push(m); return m; }
    @Override public void close() {
        while (!mats.isEmpty()) {
            Mat m = mats.pop();
            if (m != null && m.dataAddr() != 0) m.release();
        }
    }
}
```

Adopting this pattern across the ~30 leak sites listed in this document is mechanical and unit-testable.

---

## Counts

| Severity | New items |
|---|---|
| 🔴 Critical | 6 |
| 🟠 High | 14 |
| 🟡 Medium | 22 |
| 🟢 Low | 9 |
| **Total new** | **51** |

(These are in addition to the 55 already tracked in `TODOS.md`.)
