package dev.britter.maven.provenance;

import java.util.List;
import java.util.Set;

/**
 * The pure reachability rule of design §5.2/§7.1: an artifact written to the local repository is
 * PROJECT iff its file is reachable from a PROJECT root — the project dependency closure or the
 * realm closure of any PROJECT plugin/extension (plus project-supplied plugin dependencies, §7.3).
 * Everything else is IMPLICIT by omission.
 *
 * <p>Reachability is modelled as set membership over absolute file paths: the caller computes the
 * union of all PROJECT root files (closures + realms), and this class keeps exactly the observed
 * artifacts whose file is in that union. Modelling it as a union is what makes a dependency shared
 * between a PROJECT plugin and an IMPLICIT one come out PROJECT (§5.2), so it is never wrongly
 * dropped.
 *
 * <p>Pure and side-effect free, so it can be unit-tested in isolation.
 */
public final class Classifier {

    private Classifier() {
    }

    /**
     * Returns the subset of {@code universe} that is PROJECT: those artifacts whose resolved file's
     * absolute path is in {@code projectFilePaths}.
     */
    public static List<ResolvedArtifact> projectArtifacts(
            List<ResolvedArtifact> universe, Set<String> projectFilePaths) {
        return universe.stream()
                .filter(a -> a.file() != null
                        && projectFilePaths.contains(a.file().getAbsolutePath()))
                .toList();
    }
}
