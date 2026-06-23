# Test cases

Test matrix for the provenance extension (see [`design.md`](./design.md)). Each case
lists a **fixture**, an **action**, and the **expected** result. Cases are grouped; the
first group is the make-or-break (§9.1 of the design) and should be implemented and
passing before the rest.

Fixtures are small Maven projects (committed under the test resources). Where two Maven
versions are required, the harness builds the same fixture with each and compares.

The extension writes **two manifests** of identical shape (design §6), distinguished by
file name: the **project manifest** (`repo-provenance.json`, the PROJECT set) and the
**implicit manifest** (`repo-provenance-implicit.json`, the IMPLICIT set). Both validate
against [`manifest.schema.json`](./manifest.schema.json). "The project manifest" / "the
implicit manifest" are used explicitly below; an unqualified "manifest" in a project-side
case means the project manifest.

General assertions that apply to every case unless stated otherwise:

- Each manifest validates against the schema and is canonical (sorted, no timestamps).
- Re-running the same build (same Maven version) yields byte-identical manifests.
- The build result (the produced artifact) is identical to a build without the extension
  (the extension is observational, design §3).

---

## A. Version provenance (the load-bearing signal, design §5.1)

These can largely be exercised on single-module fixtures by inspecting the evidence
stream and the manifest.

1. **Pinned plugin → PROJECT.** Fixture pins `maven-compiler-plugin` to a fixed version
   in `<build><plugins>`. Expect: that plugin (and its realm closure) appears in
   the project manifest; evidence marks it PROJECT with source = project POM.
2. **Unpinned default-bound plugin → IMPLICIT.** Fixture is a plain `jar` project that
   pins nothing. Expect: the default `resources`/`compiler`/`surefire`/`jar` plugins are
   NOT in the project manifest; evidence marks them IMPLICIT (lifecycle binding / super-POM).
3. **Declared-without-version → IMPLICIT.** Fixture lists `maven-surefire-plugin` in
   `<build><plugins>` with configuration but **no `<version>`**. Expect: IMPLICIT (the
   version came from Maven, not the project).
4. **Pinned to the default value → PROJECT.** Fixture pins a plugin to exactly the
   version Maven would have bound by default. Expect: PROJECT (provenance, not value,
   decides). This is the case that proves we key on source, not version equality.
5. **Version from external parent POM → PROJECT.** Fixture inherits from an external
   parent POM (not in the reactor) that pins a plugin version. Expect: PROJECT.
6. **Version from super-POM `pluginManagement` → IMPLICIT.** A plugin whose version is
   only set by the built-in super-POM (e.g. one of the super-POM-managed plugins), not
   re-declared by the project. Expect: IMPLICIT.
7. **Build/core extension classified like a plugin.** Fixture declares a build extension
   (`<build><extensions>`) with a pinned version. Expect: PROJECT (and its realm in the
   manifest).

## B. Reachability and shared artifacts (design §5.2)

8. **Artifact shared between a PROJECT plugin realm and an IMPLICIT plugin realm →
   PROJECT.** Fixture pins a plugin whose realm shares a transitive dependency
   (same coordinates) with an implicit default plugin's realm. Expect: the shared
   artifact is in the project manifest (reachable from the kept realm).
9. **Artifact reachable only from an IMPLICIT realm → IMPLICIT.** A realm-only dependency
   of an unpinned default plugin, not used by the project or any pinned plugin. Expect:
   absent from the manifest.
10. **Project dependency coinciding with an implicit-plugin realm dep → PROJECT.** The
    project declares a dependency whose coordinates also appear in an implicit plugin's
    realm. Expect: PROJECT (reachable from the project dependency closure), so it is not
    dropped.

## C. Determinism across Maven versions (design §2, §9.2)

11. **Single-module determinism.** Build fixture A on two different Maven 3.9.x releases.
    Expect: byte-identical manifests.
12. **Multi-module determinism.** A reactor (2-3 modules, inter-module deps, mixed pinned
    and unpinned plugins) built on two Maven 3.9.x releases. Expect: byte-identical
    manifests, with the project closures unioned across modules (design §7.1).
13. **Default-version drift is invisible to the manifest.** Pick two Maven releases whose
    default plugin versions differ (e.g. different `maven-resources-plugin` default).
    Expect: the manifest is unchanged between them, even though the IMPLICIT set differs.

## D. Partition correctness (design §9.3)

14. **Exhaustive, disjoint partition across both manifests.** After a build, every
    non-volatile file under the local repository is covered by exactly one entry's `files`
    in **either** the project manifest **or** the implicit manifest. Expect: the two
    manifests' file sets are disjoint, and their union equals the repository's non-volatile
    files — no file in both, none missing.
15. **Metadata classification (design §5.3).** `maven-metadata-*.xml`, `_remote.repositories`,
    `*.lastUpdated`, `resolver-status.properties` are handled per the design (volatile →
    excluded). Expect: none of these volatile files appear in **either** manifest.

## E. Offline self-containment (design §9.5)

16. **PROJECT set + IMPLICIT set builds offline.** Materialize the PROJECT set (from the
    manifest) into one local repo and the IMPLICIT set into another; run
    `mvn package -o` against the combination. Expect: success.
17. **Parent-POM lineage included.** A fixture with a project dependency that has a chain
    of parent POMs. Expect: the manifest's PROJECT files include the full parent-POM
    lineage of that dependency (so step 16 can resolve it offline).
18. **Cross-version offline replay.** Build fixture on Maven X to produce the manifest;
    materialize the PROJECT set; run the offline build on Maven Y supplying Y's IMPLICIT
    set. Expect: success without recomputing the PROJECT set. (This is the end-to-end
    proof of the whole idea.)

## F. Gray zones (design §8) — characterize, don't necessarily "fix"

19. **Surefire provider is IMPLICIT and surfaced.** Fixture has JUnit 5 tests. Expect:
    the `surefire-junit-platform` provider (and its closure) is classified IMPLICIT and
    is listed distinctly in the evidence stream. Document the observed behavior.
20. **Provider classification is consistent across frameworks.** Variants with JUnit 4
    and TestNG. Expect: each framework's provider is classified IMPLICIT and surfaced;
    no provider leaks into the project manifest as PROJECT.

## G. Robustness / no-side-effects

21. **No-op equivalence.** Build a fixture with and without the extension. Expect:
    identical produced artifacts; the extension only adds the manifest/evidence outputs.
22. **Trivial projects.** A project with no dependencies and no tests; a `pom`-packaging
    project. Expect: valid (possibly empty) manifest, no crash.
23. **Packaging variants.** `jar`, `war`, and `pom` fixtures. Expect: correct
    classification for each lifecycle's implicit plugins.
24. **Missing/!writable output path, malformed config.** Expect: a clear failure (or
    documented fallback) that does not corrupt the build.

## H. Implicit manifest (the Maven-determined set, design §6.2/§6.3)

The implicit manifest is a first-class output, not just the complement-by-omission. These
cases assert it directly.

25. **Implicit manifest is emitted.** For a normal build, a second manifest file (the
    implicit manifest) is written alongside the project manifest, validates against the
    schema, and is canonically sorted. Expect: both files exist; for a build that uses any
    default plugin the implicit manifest is non-empty.
26. **Realm-aware completeness (guards the `dependency:get` regression).** Build a plain
    `jar` fixture. Expect: the implicit manifest contains not only the default plugin jars
    (e.g. `maven-compiler-plugin`) but their **deep realm dependencies** — e.g.
    `org.codehaus.plexus:plexus-compiler-api`/`plexus-compiler-javac` and the `maven-*` API
    closure those realms load. (A coordinate-only enumeration misses these; the extension
    must not, because it observes what Maven actually resolved.)
27. **Per-version determinism (stable hash).** Re-run the same fixture under the **same**
    Maven version. Expect: the implicit manifest is byte-identical between runs, so a hash
    taken over it is stable until Maven is bumped.
28. **Cross-version variance (the dual of §9.2).** Build the same fixture under two Maven
    versions whose default plugin versions differ. Expect: the implicit manifests **differ**
    (the implicit set tracks the Maven version) **while the project manifest is
    byte-identical** (cross-check with C.11/C.13). The two manifests must move
    independently.
29. **No-test project.** A fixture with no tests. Expect: the implicit manifest carries the
    default plugins but **no surefire provider** (none was triggered) — provider presence is
    driven by the project's test setup, not unconditional.

## I. Surefire providers in the implicit manifest (focus)

The surefire test provider is the canonical project-driven-but-Maven-versioned artifact: it
is selected by the project's test framework but versioned with surefire (design §8). It is
IMPLICIT when surefire is unpinned, and must land in the **implicit manifest** (so a shared
implicit repository can supply it), while the project's own test libraries stay PROJECT.

30. **Provider is in the implicit manifest (JUnit 5).** Fixture with JUnit 5 tests, surefire
    unpinned. Expect: the provider adapter `org.apache.maven.surefire:surefire-junit-platform`
    and its realm closure appear in the **implicit manifest** (not merely surfaced in the
    evidence stream, cf. F.19).
31. **Project test libraries are PROJECT, not implicit.** Same fixture. Expect: the project's
    declared test dependencies (`org.junit.jupiter:junit-jupiter*`, and `junit-bom` if
    imported) appear in the **project manifest** and **not** in the implicit manifest.
32. **Per-framework providers.** Variants with JUnit 4 (expect `surefire-junit4` /
    `surefire-junit47`) and TestNG (expect a TestNG provider). Each framework's provider
    adapter is in the implicit manifest; the framework libraries themselves are PROJECT.
33. **Provider transitive launcher reachability (the subtle one).** For the JUnit 5 fixture,
    `org.junit.platform:junit-platform-launcher` (whose version tracks the project's JUnit).
    Expect: classified by §5.2 reachability — PROJECT if reachable from the project's
    declared JUnit closure, IMPLICIT if pulled only through the provider realm — and present
    in **exactly one** manifest, never both. Document which path the tested Maven versions
    take.
34. **Pinning surefire flips the provider to PROJECT.** Fixture pins
    `maven-surefire-plugin` to a fixed version. Expect: surefire (and its realm, including
    the provider) is PROJECT, so the provider now appears in the **project manifest** and
    not the implicit one (it follows the plugin's provenance, §5.1/§5.2). Direct contrast
    with case 30.
35. **Provider version tracks Maven; JUnit version tracks the project.** Build the JUnit 5
    fixture under two Maven versions with different bundled surefire. Expect: the provider
    adapter's version in the implicit manifest changes with Maven, while the project's JUnit
    version in the project manifest is unchanged. (This is precisely why the provider must
    be implicit, not project.)
36. **Multi-framework reactor: union + purification (probe shape).** A reactor with one
    module per test framework (JUnit 4, JUnit 5, TestNG), surefire unpinned. Expect: the
    aggregated implicit manifest contains **all** providers, and **none** of the modules'
    declared test libraries (those are PROJECT). This mirrors how a comprehensive probe
    yields the global implicit set.
37. **Offline replay with the provider from the implicit set.** JUnit 5 fixture: split the
    local repo into the PROJECT set (project manifest) and the IMPLICIT set (implicit
    manifest), recombine, and build offline. Expect: the build succeeds **and the tests
    actually run** (not skipped) — proving the provider in the implicit set is sufficient to
    execute tests offline. Complements E.16/E.18.
