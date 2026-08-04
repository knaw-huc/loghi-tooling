# TODOS — loghi-tooling Code Review

Generated: 2026-03-24  
Scope: 427 Java source files across 8 modules (altoxmlutils, imageanalysiscommon, layoutanalyzer, layoutds, loghiwebservice, minions, pagexmlutils, stringtools)

---

## 🔴 Critical (Security / Data Loss)

### SEC-01 — Hardcoded API key in `BaseMinion`
**File:** `minions/src/main/java/.../minions/BaseMinion.java:29`  
The default API key `"471c6c6c-aee9-485b-9d64-a0a82a2936ba"` is committed in source code and used as a fallback when no key is passed via CLI.  
**Action:** Remove the default value; throw `IllegalStateException` or require the key as a mandatory argument. Rotate the key if it is still active.

### SEC-02 — `SessionFactorySingleton` is not thread-safe
**File:** `layoutds/src/main/java/.../SessionFactorySingleton.java:9`  
`getSessionFactory()` uses a naive null-check without synchronization. Two threads can both observe `null` and both build a `SessionFactory`, causing connection pool leaks.  
**Action:** Use a static initializer (`static { ... }`) or `synchronized` + double-checked locking with a `volatile` field, or replace with Hibernate's built-in boot APIs.

### SEC-03 — XML factories created without XXE protection
**Files:**  
- `stringtools/.../StringTools.java:209, 476`  
- `layoutanalyzer/.../LayoutAnalyzer.java:65, 77`  
- `pagexmlutils/.../PageUtils.java:260`

`DocumentBuilderFactory.newInstance()` and `TransformerFactory.newInstance()` are never hardened against XML External Entity injection.  
**Action:** For each factory, set `factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)` (or equivalent JAXP constants), especially before accepting any externally-supplied XML.

### SEC-04 — `BaseMinion.logIn()` can return `null`
**File:** `minions/src/main/java/.../minions/BaseMinion.java:122`  
When the login HTTP call returns a non-204 status, the method returns `null`. Callers that add the result as an `Authorization` header will issue requests with a `null` auth token — the error is silently swallowed.  
**Action:** Throw a typed exception (e.g., `AuthenticationException`) instead of returning `null`, and handle it in all callers.

---

## 🟠 High Priority (Code Smells / Reliability)

### SMELL-01 — God classes: split or extract
The following classes violate the Single Responsibility Principle and are too large to review, test, or extend safely:

| File | Lines | Problem |
|------|-------|---------|
| `layoutanalyzer/.../LayoutProc.java` | 3 844 | 70+ static methods covering baselines, margins, reading order, image binarization |
| `pagexmlutils/.../PageUtils.java` | 2 247 | XML I/O, coordinate math, region utilities, colour operations all mixed |
| `layoutds/.../DAO/DocumentImageDAO.java` | 1 888 | Query logic for dozens of unrelated access patterns in one DAO |
| `altoxmlutils/.../AltoUtils.java` | 1 002 | DOM walking + model construction, no SLF4J logging |
| `imageanalysiscommon/.../DocumentTypeConverter.java` | ~750 | Four separate format converters (Page↔Alto, Page↔hOCR, etc.) in one class |

**Action per class:**
- `LayoutProc`: extract `BaselineExtractor`, `MarginsDetector`, `ReadingOrderCalculator`, and `BinarizationHelper`.
- `PageUtils`: split into `PageXmlReader`, `PageXmlWriter`, `CoordUtils`, `PageRegionHelper`.
- `DocumentImageDAO`: group related query methods into focused query objects or sub-DAOs (e.g., `DocumentImageQueryDAO`, `DocumentImageStatsDAO`).
- `DocumentTypeConverter`: split into `PageToAltoConverter`, `AltoToPageConverter`, `PageToHocrConverter`, `HocrToPageConverter`.

### SMELL-02 — `ARUProcessor.java` is 1 334 lines of dead code
**File:** `layoutanalyzer/src/main/java/.../ARUProcessor.java`  
Every single line is commented out; the class does not compile. It consumes IDE index memory and confuses readers.  
**Action:** Delete the file. The code is in Git history if it is ever needed.

### SMELL-03 — 186+ `System.out.println` / `System.err.println` in production code
**Primary offender:** `altoxmlutils/.../AltoUtils.java` (40+ calls)  
Other offenders: `LayoutAnalyzer`, `BaseMinion`, `MinionExtractBaselines`, `MinionLoghiHTRMergePageXML`, `DocumentImageSetService`, etc.  
**Action:** Replace every production `System.out`/`System.err` call with an SLF4J `LOG.debug` / `LOG.warn` / `LOG.error`. SLF4J is already on the classpath in every module. `AltoUtils` should add a `private static final Logger LOG = LoggerFactory.getLogger(AltoUtils.class);`.

### SMELL-04 — 26 `e.printStackTrace()` calls without proper logging
**Files:** BaseMinion, all MinionExtractBaselines* variants, MinionShrinkTextLines, MinionShrinkRegions, MergeBaseLines, DocumentImageSetService, GenericDAO, StringTools, WebTooling  
**Action:** Replace with `LOG.error("Description of the failure context", e)`.

### SMELL-05 — `getBoundingBox()` duplicated three times in `DocumentTypeConverter`
**File:** `imageanalysiscommon/.../DocumentTypeConverter.java` (three near-identical private methods for `TextRegion`, `TextLine`, `Word`)  
**Action:** Extract a single generic helper:  
```java
private static Rect getBoundingBox(String coordPoints) { ... }
```
Each caller passes `element.getCoords().getPoints()`.

### SMELL-06 — `ObjectMapper` / `XmlMapper` created inside hot paths
**Files:**
- `BaseMinion.java:72, 94` — `new ObjectMapper()` inside `while(true)` retry loop
- `MinionLoghiHTRMergePageXML.java:349` — `new ObjectMapper()` per processed line
- `MinionConvertOCRResult.java:320, 358` — `new XmlMapper()` per processed file

`ObjectMapper` is thread-safe after construction and very expensive to create.  
**Action:** Declare a `private static final ObjectMapper MAPPER = new ObjectMapper();` (or `XmlMapper`) constant, or inject a shared instance.

### SMELL-07 — Infinite retry loops without a back-off ceiling
**File:** `minions/.../BaseMinion.java:58, 85`  
`getDocumentImage()` and `getOCRJob()` loop forever on `IOException`, incrementing `sleeplength` by 500 ms each attempt. There is no maximum retry count and the sleep length grows without bound.  
**Action:** Add a configurable `MAX_RETRIES` constant; throw after reaching it. Use exponential back-off with a cap (e.g., max 30 s). Replace `Thread.sleep(sleeplength)` with the capped version.

### SMELL-08 — Static mutable state without synchronisation
Multiple classes use `static` mutable fields accessed from multi-threaded contexts:
- `BaseMinion.sleeplength` — mutated per-request; races in multi-threaded minion usage
- `GenericDAO.random` — lazy-init without `volatile` or `synchronized`
- `LayoutAnalyzer._outputDebug`, `_outputFile`, `globalcounter` — modified via static setters, no sync

**Action:**  
- `sleeplength` → make it a local variable inside each loop, not a static field.  
- `GenericDAO.random` → use `ThreadLocalRandom.current()` (no shared state needed at all).  
- `LayoutAnalyzer` static flags → pass as constructor/method parameters or use an immutable config record.

### SMELL-09 — Broad `catch (Exception e)` in 20+ places
Silently swallowing exceptions means processing errors are undetectable:
- `StringTools.java:373` — `catch (Exception ignored)`
- All resource handlers in `loghiwebservice` (`LoghiHTRMergePageXMLResource`, `CutFromImageBasedOnPageXMLNewResource`, etc.)
- Multiple minions (`MinionExtractBaselines`, `MinionShrinkTextLines`, etc.)

**Action:** Catch the specific exceptions documented by each method; propagate the rest. Where the intent is genuinely "log and continue", use `LOG.error("…", e)` instead of swallowing.

### SMELL-10 — `altoxmlutils` source directory uses dot-separated path
**Directory:** `altoxmlutils/src/main/java/nl.knaw.huc.di.images.altoxmlutils/`  
The source directory name uses dots instead of slashes, which is non-standard and will confuse IDEs and Maven when source roots are set up automatically.  
**Action:** Rename to `nl/knaw/huc/di/images/altoxmlutils/` (standard Java package directory structure).

---

## 🟡 Medium Priority (Testing Gaps)

### TEST-01 — `altoxmlutils` has zero unit tests
**Module:** `altoxmlutils`  
`AltoUtils.java` (1002 lines) has no test coverage whatsoever.  
**Action:** Create `src/test/java/nl/knaw/huc/di/images/altoxmlutils/AltoUtilsTest.java`. Cover at minimum: round-trip ALTO XML serialisation, `getHYP()`, `parseSubsType()`, and the unknown-type warning paths.

### TEST-02 — `DocumentTypeConverter` has an explicit TODO to add tests
**File:** `imageanalysiscommon/.../DocumentTypeConverter.java:41`  
Comment: `// TODO RUTGERCHECK: implement all below and add unit tests`  
**Action:** Add tests for all four conversion paths: `pageToDocumentPage`, `documentPageToPage`, `documentPageToAlto`, `altoDocumentToDocumentPage`. Use small fixture XML files as inputs.

### TEST-03 — No tests for 13 out of 20 minions
The following minions have no corresponding test class:
`MinionConvertOCRResult`, `MinionConvertPageToTxt`, `MinionConvertToPdf`, `MinionDetectLanguageOfPageXml`, `MinionExtractBaselinesStartEndNew`, `MinionExtractBaselinesStartEndNew2`, `MinionExtractBaselinesStartEndNew3`, `MinionGarbageCharacterCalculator`, `MinionGeneratePageImages`, `MinionPyLaiaMergePageXML`, `MinionShrinkRegions`, `MinionShrinkTextLines`, `MinionSplitPageXMLTextLineIntoWords`  
**Action:** Start with pure-logic methods (coordinate math, string processing) that can be unit-tested without OpenCV. Add `@Tag("integration")` for tests that need native libraries.

### TEST-04 — `layoutds` root-level tests are DB-dependent and out of package
**Files:** `DocumentImageDAOTest`, `DocumentImageTest`, `ConfigurationDAOTest`, `KrakenModelDAOTest`, `Tesseract4ModelDAOTest` (all in default package)  
These tests require a live PostgreSQL connection via `SessionFactorySingleton`.  
**Action:**  
1. Move all test classes into a proper package matching the class under test.  
2. Extract query logic into testable pure-Java methods and test those with Mockito.  
3. Tag the DB-requiring tests with `@Tag("integration")` and skip them in normal `mvn test` runs.

### TEST-05 — Mixed JUnit 4 and JUnit 5 across modules
`layoutds`, `imageanalysiscommon`, `stringtools`, `layoutanalyzer`, and `pagexmlutils` use JUnit 4.13.2.  
`minions` declares **both** JUnit 4 and JUnit 5 in the same POM.  
`loghiwebservice` uses JUnit 5 only.  
**Action:** Standardise all modules to JUnit 5 (`junit-jupiter`). Remove JUnit 4 dependencies. Migrate `@RunWith`, `@Before`/`@After` to JUnit 5 annotations during migration.

### TEST-06 — `ConnectedComponentProc` empty-list edge case unconfirmed
**File:** `imageanalysiscommon/.../ConnectedComponentProc.java:24, 37, 50`  
Comments: `// TODO RUTGERCHECK: is 0 appropriate for empty list` appear in three places.  
**Action:** Decide whether 0 is the correct sentinel; write parameterised tests for the empty-list case.

---

## 🟡 Medium Priority (Dependencies & Build)

### DEP-01 — `layoutds` dependency declared three times in `altoxmlutils/pom.xml`
**File:** `altoxmlutils/pom.xml:53, 62, 74`  
`nl.knaw.huc.di.images:layoutds:2.1.0` is declared as a dependency at lines 53, 62 and 74. Two of the three have `<scope>compile</scope>`.  
**Action:** Remove the two duplicate declarations, keeping only one entry.

### DEP-02 — `joda-time` at three different versions across modules
| Module | Version |
|--------|---------|
| altoxmlutils | 2.9.2 |
| minions | 2.10.8 |
| layoutds, pagexmlutils | 2.12.5 |

**Action:** Declare `joda-time` once in the parent POM `<dependencyManagement>` section at the latest version (2.12.5) and remove all module-level version declarations.

### DEP-03 — `commons-io` at three different versions across modules
`altoxmlutils=2.8.0`, `layoutanalyzer/pagexmlutils=2.11.0`, `imageanalysiscommon/loghiwebservice=2.13.0`  
**Action:** Same as DEP-02 — manage in the parent POM at 2.13.0.

### DEP-04 — No parent `<dependencyManagement>` BOM
The parent `pom.xml` does not have a `<dependencyManagement>` block. Each module pin its own dependency versions independently, causing the version drift above.  
**Action:** Add a `<dependencyManagement>` section to the root `pom.xml` covering at minimum: Jackson, joda-time, commons-io, commons-lang3, SLF4J/Log4j2, Guava, OpenCV, JUnit 5.

### DEP-05 — Elasticsearch High-Level REST Client is deprecated
**File:** `minions/pom.xml`  
`elasticsearch-rest-high-level-client:7.17.16` was deprecated by Elastic in 2022 and is scheduled for removal in ES 9. It is also a large transitive dependency.  
**Action:** If Elasticsearch integration is still needed, migrate to the official [Elasticsearch Java Client](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html) (`co.elastic.clients:elasticsearch-java`). If not needed, remove the dependency.

### DEP-06 — H2 test database at version 1.4.200 (2019 release)
**File:** `layoutds/pom.xml`  
H2 1.4.200 has known CVEs and is incompatible with Hibernate 6. H2 2.x is the current release.  
**Action:** Upgrade to `com.h2database:h2:2.2.224` (or later). Note that H2 2.x made breaking changes to DDL; test the schema creation scripts.

### DEP-07 — Maven compiler still targeting Java 11
All modules set `<source>11</source><target>11</target>`.  
**Action (optional, plan for next major release):** Upgrade target to Java 17 LTS. This enables:  
- Records for lightweight DTOs in `layoutds` (replace 50+ field-only entity-like classes)  
- Pattern matching `instanceof` (remove many explicit casts in `DocumentTypeConverter`)  
- Text blocks for embedded XML/JSON test fixtures  
- `jakarta.persistence` namespace alignment for Hibernate 6 migration

---

## 🟡 Medium Priority (Optimisations)

### OPT-01 — OpenCV `Mat` memory leak risk: 89 allocations vs 59 releases
**Files:** `LayoutProc.java`, `DocumentPage.java`, `LayoutAnalyzer.java`, all `MinionExtract*` variants  
Native OpenCV `Mat` objects hold off-heap memory that the GC cannot reclaim. With 89 `new Mat()` calls and only 59 `mat.release()` calls in production code, there are at least 30 allocation sites with no corresponding release.  
**Action:**  
1. Audit every `new Mat()` site and ensure a `mat.release()` in a `finally` block or try-with-resources (using a wrapper class).  
2. Consider a `MatWrapper implements AutoCloseable` helper that calls `mat.release()` on `close()`.  
3. Focus first on the `DocumentPage` image cache fields (`binaryImage`, `grayImage`, `cannyImage`, etc.) which accumulate for every processed page.

### OPT-02 — `TransformerFactory.newInstance()` called per XML output
**Files:** `LayoutAnalyzer.java:65, 77`; `StringTools.java:220`; `PageUtils.java:260`  
`TransformerFactory` is expensive to create; it does classpath scanning. Creating it per-call adds measurable overhead for batch processing.  
**Action:** Make the factory a `static final` field in each class, or use a thread-local/pool approach.

### OPT-03 — `DocumentBuilderFactory.newInstance()` called per XML parse
**Files:** `StringTools.java:209, 476`  
Same issue as OPT-02. `DocumentBuilderFactory` should be a shared `static final` field.

### OPT-04 — `Joda-Time` used where `java.time` is available
`joda-time` is used in `altoxmlutils`, `layoutds`, `minions`, and `pagexmlutils` — all of which target Java 11, which already ships `java.time`. Joda-Time is in maintenance mode since 2022.  
**Action:** Replace `org.joda.time.DateTime` / `LocalDateTime` / `DateTimeZone` with `java.time.ZonedDateTime` / `LocalDateTime` / `ZoneId`. Remove the `joda-time` dependency from all four modules.  
*(Do this module by module; start with `stringtools` which has a `DateConversionTest` to validate the migration.)*

### OPT-05 — `StyledString` TODO: use `SortedSet` for `styledCharList`
**File:** `pagexmlutils/.../StyledString.java:49, 314`  
Comments note the list should be a `SortedSet` for correct ordering of overlapping styles.  
**Action:** Replace `List<StyledChar>` with `TreeSet<StyledChar>` (implementing `Comparable` on character offset). Add a test that verifies overlapping style regions are sorted correctly.

---

## 🟢 Low Priority (Naming / Typos)

### TYPO-01 — `UnicodeToAsciiTranslitirator` — misspelled class name
**File:** `imageanalysiscommon/.../UnicodeToAsciiTranslitirator.java`  
Correct spelling: `Transliterator`.  
Used in: `PageUtils`, `MinionLoghiHTRMergePageXML`, `MinionExtractBaselinesStartEndNew3`  
**Action:** Rename class to `UnicodeToAsciiTransliterator` and update all import/usage sites. The public constant `PageUtils.UNICODE_TO_ASCII_TRANSLITIRATOR` should be renamed to `UNICODE_TO_ASCII_TRANSLITERATOR`.

### TYPO-02 — `Hypenation` — misspelled class name
**File:** `layoutds/.../Alto/Hypenation.java`  
Correct spelling: `Hyphenation`.  
Used in: `AltoUtils`, `DocumentTypeConverter`  
**Action:** Rename class and all usages.

### TYPO-03 — `DicoveredLabel` — misspelled enum name
**File:** `layoutds/.../connectedComponent/DicoveredLabel.java`  
Correct spelling: `DiscoveredLabel`.  
Used in: `ConnectedComponent`, `LineDescriptor`  
**Action:** Rename enum and all usages.

### TYPO-04 — `LayoutAnalyzer.documentAsString` error message typo
**File:** `layoutanalyzer/.../LayoutAnalyzer.java:70`  
Returns `"an error has occured"` — should be `"occurred"`.  
**Action:** Fix the string and convert to a logged exception.

### TYPO-05 — `DocumentImageSetService` FIXME comment references non-existent ticket
**File:** `layoutds/.../services/DocumentImageSetService.java:88`  
`// FIXME TI-351: create complete fix` — the workaround has been present long enough to become canonical.  
**Action:** Either implement the fix or document why the workaround is acceptable and remove the FIXME.

---

## 🟢 Low Priority (Existing TODOs to Track)

These are pre-existing `TODO` / `FIXME` comments in the source that should be tracked in the issue tracker rather than only in source:

| Ref | File | Note |
|-----|------|------|
| LAY-01 | `LayoutProc.java:499` | `//TODO detect Orientation` |
| LAY-02 | `LayoutProc.java:1168` | `//TODO what to do with cocos connected to two different line segments?` |
| LAY-03 | `LayoutProc.java:1747` | `//TODO: deal with rotated images` |
| LAY-04 | `LayoutProc.java:2754, 2987` | `// FIXME: also includes vertical and upside-down lines` |
| LAY-05 | `LayoutProc.java:2801` | `// TODO: fixme. Due to rotation x or y might fall outside the image` |
| LAY-06 | `LayoutProc.java:3048` | `// TODO: this line sometimes throws an error` |
| LAY-07 | `LayoutProc.java:3284` | `// TODO: workaround for lines rotated too much` |
| LAY-08 | `LayoutProc.java:3499, 3514` | `// FIXME TI-541` |
| LAY-09 | `LayoutProc.java:3711` | `//TODO: split merged text lines` |
| DOC-01 | `DocumentPage.java:296` | `//TODO RUTGERCHECK Implement` — `getComposedBlocks()` returns empty list |
| DOC-02 | `DocumentPage.java:401, 411` | Two more unimplemented methods |
| MIN-01 | `MinionCutFromImageBasedOnPageXMLNew.java:571` | `//TODO: should not abort the flow` |
| MIN-02 | `MinionCutFromImageBasedOnPageXMLNew.java:587-590` | Four TODO options for xheight detection — pick one |
| MIN-03 | `MinionExtractBaselinesStartEndNew3.java:345` | `//TODO asSingleRegion parameter` |
| WS-01 | `RecalculateReadingOrderNewResource.java:53` | `// TODO implement errorFileWriter usage` |
| DS-01 | `DocumentTextLineSnippetDAO.java:20, 154` | Methods marked "method is not used" — remove or implement |
| DS-02 | `Collection.java:16` | `// TODO RUTGERCHECK: add recursive collections` |
| DS-03 | `Alto/ProcessingSoftware.java:5` | `//TODO: very similar to OCRProcessingSoftware — maybe refactor?` |
| IMG-01 | `VisualizationHelper.java:94, 126` | `// TODO TI-374: don't use put` — performance optimisation |

---

## Summary Counts

| Priority | Items |
|----------|-------|
| 🔴 Critical | 4 |
| 🟠 High | 10 |
| 🟡 Medium — Testing | 6 |
| 🟡 Medium — Dependencies | 7 |
| 🟡 Medium — Optimisation | 5 |
| 🟢 Low — Naming | 5 |
| 🟢 Low — Existing TODOs | 18 |
| **Total** | **55** |

---

## Suggested Order of Attack

1. **SEC-01** (hardcoded key) — fix in < 30 min; security risk.
2. **SEC-02** (SessionFactory thread-safety) — fix in < 1 h; risk of production connection-pool exhaustion.
3. **SMELL-02** (delete ARUProcessor) — 5-minute clean-up with outsized readability benefit.
4. **DEP-01** (triple `layoutds` dependency) — 2-minute fix.
5. **DEP-04** → **DEP-02** → **DEP-03** (parent BOM + version alignment) — foundational change; enables consistent upgrades.
6. **SMELL-03** (System.out → SLF4J) — high-volume mechanical change; use IDE "replace all" + add Logger fields.
7. **TEST-01** (altoxmlutils tests) — no module should have zero tests.
8. **TEST-05** (JUnit 4→5 standardisation) — do before adding more tests.
9. **OPT-01** (Mat memory leak audit) — important for long-running batch jobs.
10. **SMELL-01** (god class splits) — large refactor; do incrementally with tests first.

