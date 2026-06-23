# Design: Maven Local-Repository Provenance Extension

## 1. Background

When Maven builds a project, it populates the local repository (`-Dmaven.repo.local`)
with everything it resolves: the project's dependency closure, the plugins it runs,
those plugins' realm dependencies, and all the POM/metadata read during resolution.
These artifacts come from two distinct sources:

- **Project-determined**: things the project's own POMs decide — its declared
  dependencies, and any plugin/extension whose version the project (or a parent POM it
  inherits) specifies. This set is a pure function of the project's POMs.
- **Maven-determined (implicit)**: plugins Maven binds on the project's behalf when the
  project does *not* pin them — the default lifecycle bindings and the built-in
  super-POM's `pluginManagement` — plus everything reachable only through those plugins'
  realms. This set changes from one Maven distribution to another.

We want a **Maven core extension** that, during an ordinary build, observes every
artifact written to the local repository and classifies it as project-determined or
Maven-determined, then emits that classification.

## 2. Central invariant (the reason this exists)

> The project-determined set must be **identical regardless of which Maven version runs
> the build**, given the same project sources.

Everything in the contract serves this invariant. If two builds of the same project on
different Maven 3.9.x releases produce different project-determined sets, the extension
is wrong.

## 3. Scope, invocation, environment

- Target: **Maven 3.9.x** (the line that supports core extensions via
  `.mvn/extensions.xml` and resolver chaining). State the minimum tested version.
- Packaged as a **core extension** loaded for the whole reactor (`.mvn/extensions.xml`),
  so it is active before project model building and through the end of the build.
- The extension is **observational**: it must not change build behavior, artifact
  resolution results, or build output. It only watches and reports.
- Configuration is taken from system properties (define names, e.g.
  `repoprovenance.output`). Keep configuration minimal: at least an output path for the
  manifest.

## 4. Definitions

- **Artifact**: identified by `groupId:artifactId:version`, with a type/extension and
  optional classifier. Its on-disk footprint in the local repository includes the
  primary file (e.g. `.jar`), its `.pom`, and checksum sidecars.
- **Project model lineage**: the project POM plus the chain of parent POMs it inherits
  from — **excluding** Maven's built-in super-POM. External/corporate parent POMs are
  part of the lineage.
- **Plugin/extension version provenance**: the source of the effective `<version>` of a
  plugin or build/core extension.
- **Realm**: the isolated dependency set Maven assembles to execute a given
  plugin/extension.

## 5. Classification rules

### 5.1 Provenance of a plugin/extension version

A plugin (or extension) is **PROJECT** iff its effective version originates from the
project model lineage (§4). It is **IMPLICIT** iff its version originates from:

- the built-in super-POM's `pluginManagement`, or
- a default lifecycle binding with no version in any POM (the version is supplied by the
  lifecycle mapping, not the model).

Corollaries the implementer must honor:

- A plugin the project declares **without** a version (inherits the default) → **IMPLICIT**.
- A plugin the project pins to a version that happens to **equal** the Maven default →
  **PROJECT** (provenance, not value, decides).
- A version pinned by an **external parent POM** the project inherits → **PROJECT** (it's
  fixed by the project's lineage, independent of the Maven distribution).

The expected source of this signal is Maven's model source tracking (`InputLocation` on
the `version` element); a version element with no model location is the "pure lifecycle
binding" case → IMPLICIT. The first prototype task (§9.1) is to confirm this signal is
reliable for the default-bound plugins.

### 5.2 Artifact classification by reachability (handles shared artifacts)

Compute two roots:

- the project's **dependency closure** (all scopes the build uses), and
- the **realm closure of each PROJECT plugin/extension**.

Then, for every artifact written to the local repository:

- **PROJECT** if it is reachable from the project dependency closure **or** from any
  PROJECT plugin's realm closure;
- **IMPLICIT** otherwise (reachable only via IMPLICIT plugin realms, or pulled by Maven
  core infrastructure).

Default is IMPLICIT: an artifact is PROJECT only if something explains it via the
project. This union/reachability rule is what makes a dependency shared between (say) a
pinned plugin and an implicit one come out PROJECT — so it is never wrongly dropped.

### 5.3 POMs, parents, checksums, metadata

- For any artifact classified PROJECT, its `.pom`, the full **parent-POM lineage** needed
  to interpret it, and checksum sidecars are also PROJECT (offline resolution of the
  project set must be self-contained).
- `maven-metadata-*.xml` and similar resolution-state files are volatile; classify them
  IMPLICIT unless an offline resolution of the PROJECT set provably requires a given
  metadata file. The implementer should determine the minimal metadata the PROJECT set
  needs and document it.

## 6. Output contract

The extension emits a single **manifest** describing the **PROJECT-determined set**.
It deliberately does **not** list the IMPLICIT set: everything written to the local
repository that is not in the manifest is IMPLICIT by definition. Keeping the implicit
set out is what makes the manifest a pure, Maven-version-independent artifact — listing
it would make the file Maven-specific. A consumer that needs the implicit set computes it
as the **complement** (local repository minus the PROJECT set) at build time.

### 6.1 Location, timing, reactor aggregation

- Single-module and **reactor** builds alike produce **one aggregated manifest** — never
  one per module (so consumers read a single file, not a tree of per-module dirs).
- Default location: the reactor execution root's build directory, i.e.
  `<root>/target/repo-provenance.json` (the top-level project's
  `${project.build.directory}`). Overridable via the `repoprovenance.output` system
  property (absolute path).
- Written **once, at session end**, after all modules have built, so it is complete.
  (Writing under `target/` is safe because it happens after the build; a `clean` earlier
  in the same invocation does not affect it.)
- In a reactor the PROJECT set is the **deduplicated union over all modules** (an artifact
  that is PROJECT in any module is PROJECT — the reachability union of §5.2 / §7.1); each
  artifact appears at most once.

### 6.2 Format and determinism

- The manifest is **JSON** and MUST validate against
  [`manifest.schema.json`](./manifest.schema.json).
- It MUST be **canonical and diff-friendly**: `projectArtifacts` sorted by
  `(groupId, artifactId, version, type, classifier)`, each entry's `files` sorted,
  UTF-8/LF, and **no timestamps or run-specific data**.
- Because it lists only the PROJECT set, the **whole manifest is
  Maven-version-independent**: two builds of the same project sources on any Maven 3.9.x
  release MUST produce a byte-identical manifest (acceptance test §9.2).
- Each entry identifies an artifact by full coordinates and lists the repository-relative
  files that belong to it (primary file, `.pom`, checksums).

### 6.3 Diagnostics report (separate file, non-normative, Maven-specific)

Separately from the manifest, the extension emits a **report** (its own file, e.g.
`<root>/target/repo-provenance-report.json`; **not** covered by the schema and explicitly
*not* required to be stable across Maven versions). It carries:

- **`warnings`** — anything the extension could not classify with confidence (e.g.
  undeterminable plugin version provenance). Consumers use this for the fallback decision
  (§11).
- per plugin/extension **provenance evidence** (version + PROJECT/IMPLICIT + source) for
  validating §5.1 and surfacing the §8 gray-zone artifacts.
- optionally, the IMPLICIT set, as a convenience for consumers building a
  per-Maven-version shared repository. (It is Maven-specific, which is exactly why it
  lives here and not in the manifest.)

The report MUST NOT be relied upon as the deterministic contract output; only the manifest
is. The extension does **not** move, copy, prune, or fetch anything; acting on either
output is the consumer's job and out of scope.

## 7. Required behaviors / edge cases

1. **Multi-module reactors**: union the project dependency closures and PROJECT-plugin
   realms across all modules; classify against the union.
2. **Build/core extensions** (not just lifecycle plugins): classify by version
   provenance exactly like plugins.
3. **Plugin with project-supplied extra dependencies** (`<plugin><dependencies>`): these
   are declared by the project, so they (and their closure) are **PROJECT even when the
   plugin itself is IMPLICIT** — their versions are project-controlled, so they belong in
   the (stable) project set, and classifying them PROJECT is also what keeps the implicit
   universe bounded (otherwise a project could inject arbitrary artifacts into the implicit
   set). A pinned plugin's *own* realm follows the plugin (PROJECT).
4. **A plugin used both implicitly and as pinned across different modules**: classify
   per-module by that module's provenance; a realm artifact is PROJECT if any PROJECT
   occurrence reaches it (reachability union).
5. **Idempotence / no side effects**: a build with the extension must produce the same
   build result as without it.

## 8. Known gray areas (surface them; don't silently mis-handle)

- **Dynamically resolved plugin dependencies** — the canonical case is the surefire test
  **provider** (e.g. JUnit-platform vs JUnit4 vs TestNG), which an IMPLICIT plugin selects
  *at runtime based on the project's test dependencies*, at a version tied to the plugin
  version. By §5.2 these come out IMPLICIT (realm-reachable only from an implicit plugin).
  The extension must classify them deterministically and **list them distinctly** in the
  evidence stream so the consumer can decide how to supply them. Do not try to reclassify
  them as PROJECT.
- **Version ranges / SNAPSHOT** in project dependencies make the project set itself
  non-deterministic independent of this extension; out of scope, but the manifest should
  faithfully reflect whatever was resolved.

## 9. Acceptance criteria

See [`test-cases.md`](./test-cases.md) for the concrete test matrix. The load-bearing
ones:

1. **Provenance probe (do this first):** an observational run on a real multi-module
   project that dumps each resolved plugin/extension with its version and provenance
   source. Confirm default-bound plugins are reliably distinguishable from project-pinned
   ones. If this signal isn't clean, the rest doesn't stand.
2. **Determinism:** build the same project on two different Maven 3.9.x releases; the
   manifest must be **byte-identical**.
3. **Partition correctness:** every file in the local repository is classified exactly
   once; PROJECT ∪ IMPLICIT covers the repo with no overlap.
4. **Pinned vs unpinned:** a project that pins `maven-compiler-plugin` → that plugin and
   its realm are PROJECT; the same project without the pin → IMPLICIT.
5. **Offline self-containment of the PROJECT set:** the PROJECT files plus a
   separately-provided set of the IMPLICIT plugins are sufficient to run the build
   offline (validates that the parent-POM/metadata handling in §5.3 is complete).
6. **Reactor aggregation:** a multi-module build produces exactly one manifest at the
   execution root's `target/`, deduplicated across modules; no per-module manifests.
7. **Graceful degradation:** when the extension cannot classify something confidently,
   the build still succeeds and a `warning` is recorded in the report (§6.3), so the
   consumer can fall back (§11) rather than getting a confident-but-wrong manifest.

## 10. Non-goals

- No repository partitioning, copying, or pruning (manifest only).
- No knowledge of, or dependency on, how the IMPLICIT set is supplied at replay time.
- No network/mirror/proxy configuration.
- No attempt to make the IMPLICIT set itself deterministic.

## 11. Consumer fallback (escape hatch)

This is intended as an ~80% solution. Some projects will be unclassifiable, or will hit
the §8 gray areas. For those, the consuming system retains a **fallback**: it uses the
**unpartitioned local repository** exactly as it would without this extension (a
complete, Maven-coupled dependency set) and ignores the manifest for that project.

To make that fallback safe and decidable, the extension MUST:

1. Be **strictly observational** — never change resolution results or build output, and
   never fail the build. If it cannot do its job, the build still succeeds and produces
   the normal result; the consumer falls back.
2. **Signal uncertainty instead of guessing** — when provenance or reachability cannot be
   determined confidently, record a `warning` in the report (§6.3) rather than emit a
   confident-but-wrong manifest. Consumers treat a non-empty `warnings` (or a failed
   offline replay, §9.5) as the trigger to fall back for that project.

The extension does not implement the fallback; it only guarantees the build is never
harmed and that low-confidence results are detectable.

---

## Appendix A: Reference implementation hints (non-binding)

These are pointers, not prescriptions; the implementer may choose other mechanisms as
long as the contract in §1–§11 holds.

**Packaging & wiring**
- A core extension is a jar with `META-INF/maven/extension.xml` (or relies on
  JSR-330/Sisu component discovery). Components are wired with `@Named @Singleton`.
- Load it via a `.mvn/extensions.xml` entry in the project root (preferred), or
  `-Dmaven.ext.class.path=<jar>`.

**Getting the session and registering an observer**
- Implement `org.apache.maven.eventspy.EventSpy` (or extend `AbstractEventSpy`). It
  receives `MavenExecutionRequest` early; from there you can reach / wrap the resolver
  session (`RepositorySystemSession`) and chain a custom `RepositoryListener` onto it.
- Alternatively/additionally, implement
  `org.apache.maven.AbstractMavenLifecycleParticipant`
  (`afterSessionStart`, `afterProjectsRead`, `afterSessionEnd`) for access to
  `MavenSession` and the resolved `MavenProject`s.

**Observing what hits the local repository**
- Register an Aether `org.eclipse.aether.RepositoryListener`; relevant events:
  `artifactResolved`, `artifactDownloaded`, `metadataResolved`, `metadataDownloaded`.
  Each event exposes the artifact/metadata and a `RequestTrace`.
- Attribute each resolution to its trigger by walking `RequestTrace.getData()` /
  `getParent()` up to the originating request. Plugin/realm resolutions trace back to
  plugin resolution; project dependency resolutions trace back to a project/dependency
  request. This is how you tag each resolved file PROJECT vs IMPLICIT without guessing.

**Reading version provenance (§5.1)**
- From the effective model: `Plugin.getLocation("version")` →
  `org.apache.maven.model.InputLocation` → `getSource().getModelId()`. The built-in
  super-POM has a recognizable model id (verify the exact string for the target Maven;
  it identifies the super-POM / "standalone" model). A `null` location for a
  lifecycle-bound plugin version is itself the IMPLICIT signal.
- Cross-check against the known default-plugin set if a second signal is wanted.

**Computing the roots (§5.2)**
- Project dependency closure: `MavenProject.getArtifacts()` after resolution, and/or the
  `DependencyResolutionResult`.
- Plugin realms: `org.apache.maven.plugin.MavenPluginManager` /
  `PluginDependenciesResolver`; a plugin's `ClassRealm.getURLs()` maps realm entries to
  local-repository files.

**Determinism hygiene**
- Sort the manifest; emit repository-relative POSIX paths; exclude `_remote.repositories`,
  `*.lastUpdated`, `resolver-status.properties`, and timestamps. Two runs on different
  Maven 3.9.x releases must diff clean.
