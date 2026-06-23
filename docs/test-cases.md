# Test cases

Test matrix for the provenance extension (see [`design.md`](./design.md)). Each case
lists a **fixture**, an **action**, and the **expected** result. Cases are grouped; the
first group is the make-or-break (§9.1 of the design) and should be implemented and
passing before the rest.

Fixtures are small Maven projects (committed under the test resources). Where two Maven
versions are required, the harness builds the same fixture with each and compares.
"Manifest" always means the JSON document validated against
[`manifest.schema.json`](./manifest.schema.json).

General assertions that apply to every case unless stated otherwise:

- The manifest validates against the schema.
- The manifest is canonical (sorted, no timestamps); re-running the same build yields a
  byte-identical manifest.
- The build result (the produced artifact) is identical to a build without the extension
  (the extension is observational, design §3).

---

## A. Version provenance (the load-bearing signal, design §5.1)

These can largely be exercised on single-module fixtures by inspecting the evidence
stream and the manifest.

1. **Pinned plugin → PROJECT.** Fixture pins `maven-compiler-plugin` to a fixed version
   in `<build><plugins>`. Expect: that plugin (and its realm closure) appears in
   `projectArtifacts`; evidence marks it PROJECT with source = project POM.
2. **Unpinned default-bound plugin → IMPLICIT.** Fixture is a plain `jar` project that
   pins nothing. Expect: the default `resources`/`compiler`/`surefire`/`jar` plugins are
   NOT in `projectArtifacts`; evidence marks them IMPLICIT (lifecycle binding / super-POM).
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
   artifact is in `projectArtifacts` (reachable from the kept realm).
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

14. **Exhaustive, disjoint partition.** After a build, every file under the local
    repository is either covered by exactly one `projectArtifacts` entry's `files` or is
    IMPLICIT. Expect: no file is listed twice; no PROJECT file is missing; PROJECT and
    IMPLICIT do not overlap.
15. **Metadata classification (design §5.3).** `maven-metadata-*.xml`, `_remote.repositories`,
    `*.lastUpdated`, `resolver-status.properties` are handled per the design (volatile →
    IMPLICIT / excluded). Expect: none of these volatile files appear in `projectArtifacts`.

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
    no provider leaks into `projectArtifacts` as PROJECT.

## G. Robustness / no-side-effects

21. **No-op equivalence.** Build a fixture with and without the extension. Expect:
    identical produced artifacts; the extension only adds the manifest/evidence outputs.
22. **Trivial projects.** A project with no dependencies and no tests; a `pom`-packaging
    project. Expect: valid (possibly empty) manifest, no crash.
23. **Packaging variants.** `jar`, `war`, and `pom` fixtures. Expect: correct
    classification for each lifecycle's implicit plugins.
24. **Missing/!writable output path, malformed config.** Expect: a clear failure (or
    documented fallback) that does not corrupt the build.
