# nixpkgs-maven-extension

A Maven **core extension** that observes everything a build writes to the local repository and
partitions it into **two canonical JSON manifests** of identical shape:

- the **project manifest** (`repo-provenance.json`) — the **PROJECT-determined** set: a pure function
  of the project's own POMs (declared dependencies, plus any plugin/extension whose version the
  project or an inherited parent pins). Because only the POMs determine it, it is **byte-identical
  across Maven 3.9.x releases**.
- the **implicit manifest** (`repo-provenance-implicit.json`) — the **Maven-determined (IMPLICIT)**
  set: whatever Maven binds on the project's behalf (default lifecycle plugins, the super-POM's
  `pluginManagement`, and everything reachable only through those plugins' realms). Canonically
  sorted but **Maven-version-specific** by nature.

Together they are a lossless, disjoint partition of everything the build wrote to the local
repository (minus volatile metadata). A downstream system (e.g. a Nix packaging pipeline) consumes a
real package build via its *project* manifest (a stable, Maven-independent dependency set), and a
dedicated probe build via its *implicit* manifest (to assemble the per-Maven-version shared implicit
repository).

The extension is **strictly observational**: it never changes resolution results or build output,
and never fails the build. If it cannot classify something confidently it records a warning in the
report rather than emitting a confident-but-wrong manifest, so a consumer can fall back to the
unpartitioned local repository. See [`docs/design.md`](docs/design.md) for the full contract and
[`docs/manifest.schema.json`](docs/manifest.schema.json) for the manifest schema.

## Requirements

- **Maven 3.9.6 or newer** (the 3.9.x line; tested against 3.9.6 and 3.9.12).
- Any **JDK 17 or newer** at build time.

> **Note on bytecode level.** The extension is compiled to **Java 17 bytecode** even though it runs
> happily on newer JDKs. Maven 3.9.6 bundles a Sisu/ASM that cannot scan newer class files during
> component discovery, so a higher bytecode target would make the extension silently fail to load
> there. Keep the target at 17 while 3.9.6 is supported.

## Usage

Load the extension for the whole reactor by adding `.mvn/extensions.xml` in your project root
(preferred):

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0">
  <extension>
    <groupId>dev.britter</groupId>
    <artifactId>nixpkgs-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Then build normally:

```sh
mvn package
```

Alternatively, load it ad hoc without modifying the project:

```sh
mvn -Dmaven.ext.class.path=/path/to/nixpkgs-maven-extension.jar package
```

The extension has no third-party runtime dependencies, so the single jar suffices on
`maven.ext.class.path`.

## Outputs

Written once at the end of the build, at the reactor execution root:

| File | Description |
| --- | --- |
| `target/repo-provenance.json` | The **project manifest** (above). Validates against [`docs/manifest.schema.json`](docs/manifest.schema.json). |
| `target/repo-provenance-implicit.json` | The **implicit manifest** (above) — same shape and schema. |
| `target/repo-provenance-report.json` | A **diagnostics report** — Maven-specific, *not* part of the deterministic contract. Carries warnings, per-plugin/extension provenance evidence, and the full set of observed artifacts. |

In a multi-module build each manifest is the deduplicated union over all modules — **one aggregated
file** of each kind, never one per module. The project and implicit manifests are disjoint (a file
is in at most one).

## Configuration

System properties (all optional):

| Property | Default | Description |
| --- | --- | --- |
| `repoprovenance.output` | `<execution-root>/target/repo-provenance.json` | Absolute path for the project manifest. |
| `repoprovenance.implicitOutput` | `<execution-root>/target/repo-provenance-implicit.json` | Absolute path for the implicit manifest. |
| `repoprovenance.report` | `<execution-root>/target/repo-provenance-report.json` | Absolute path for the diagnostics report. |

## How classification works

- **Version provenance (§5.1).** A plugin/extension is PROJECT iff the effective `<version>`
  originates from the project model lineage. Maven attributes a project-pinned version to the
  project/parent POM's model id, and a Maven-supplied version to a synthetic source ending in
  `:default-lifecycle-bindings` or `:super-pom`. Provenance keys on the *source*, not the version
  value — pinning a plugin to exactly Maven's default still makes it PROJECT.
- **Reachability (§5.2).** An artifact is PROJECT if its local-repository file is reachable from the
  project dependency closure **or** from any PROJECT plugin's realm closure (union), plus
  project-supplied plugin dependencies. This makes a dependency shared between a pinned plugin and an
  implicit one come out PROJECT, so it is never wrongly dropped.
- **Completeness (§5.3).** For every PROJECT artifact, its `.pom`, full parent-POM lineage, and
  checksum sidecars are PROJECT too, so the PROJECT set is self-contained for offline resolution.

## Building

```sh
mvn verify
```

This runs the unit tests and the [takari](https://github.com/takari/takari-plugin-testing-project)
integration tests, which fork **real Maven 3.9.6 and 3.9.12** builds against fixture projects and
assert — among other things — that the manifest is byte-identical across both versions.
