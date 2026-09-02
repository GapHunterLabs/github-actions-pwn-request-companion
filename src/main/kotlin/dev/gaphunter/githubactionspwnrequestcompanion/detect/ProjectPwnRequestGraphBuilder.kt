package dev.gaphunter.githubactionspwnrequestcompanion.detect

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.gaphunter.githubactionspwnrequestcompanion.model.PwnRequestFinding
import dev.gaphunter.githubactionspwnrequestcompanion.model.WorkflowFileFacts

/**
 * Aggregates [WorkflowFileParser]'s per-file facts across EVERY
 * workflow YAML file under `.github/workflows/` in the project into a
 * real reusable-workflow call graph.
 *
 * **The real vulnerable shape, and why it's cross-file:** GitHub
 * Actions forbids a job from having both `uses:` (a reusable-workflow
 * call) and its own `steps:` -- so the SAME job can never both call a
 * reusable workflow AND do its own checkout. The actual "Pwn Request"
 * shape when a reusable workflow is involved lives ENTIRELY inside the
 * CALLEE's own job (checkout of `github.event.pull_request.head...` +
 * secret usage, in the SAME job, exactly like the direct case) -- what
 * makes it dangerous is that the callee's `on:` trigger is
 * `workflow_call` (not risky on its own), and it only becomes reachable
 * with a real risky trigger's `github` event context AND real secrets
 * because some OTHER file's job calls it with `secrets: inherit`/
 * explicit secrets from a workflow whose OWN trigger IS
 * `pull_request_target`/`workflow_run` (GitHub Actions propagates the
 * ORIGINAL triggering event's `github` context through a
 * `workflow_call` chain, confirmed in GitHub's own docs) -- the
 * mechanism this graph resolves.
 *
 * **v0.1 scope, stated honestly:** only `uses: ./.github/workflows/x.yml`
 * (local reusable workflows in the SAME repo) -- never
 * `owner/repo/.github/workflows/x.yml@ref` (an external repo, out of
 * this project's own PSI/VFS reach entirely). Exactly one hop of
 * reusable-workflow resolution -- a chain of two or more reusable
 * workflows calling each other isn't followed (GitHub Actions itself
 * caps reusable-workflow nesting at 4 levels; a single hop already
 * covers the overwhelmingly common real shape).
 *
 * Cached per-project via [CachedValuesManager], same reasoning as this
 * catalog's other whole-project graph builders.
 */
object ProjectPwnRequestGraphBuilder {

    const val MAX_WORKFLOW_FILES = 200
    private const val MAX_FILE_LENGTH = 500_000

    private val CACHE_KEY: Key<CachedValue<PwnRequestIndex>> = Key.create("githubActionsPwnRequestCompanion.index")

    class PwnRequestIndex(filesByName: Map<String, WorkflowFileFacts>) {

        /** Target-reusable-workflow filename -> one real caller filename that invokes it (with secrets reachable) from a file whose OWN `on:` has a risky trigger -- computed once for the whole project graph. When a target is called this way from more than one such caller, only one example is kept (a diagnostic detail, not a correctness requirement). */
        private val propagatedFromCaller: Map<String, String> = filesByName.values
            .filter { it.riskyTriggers.isNotEmpty() }
            .flatMap { caller ->
                caller.jobs.filter { it.reusableCallSecretsReachable }
                    .mapNotNull { it.reusableCallTargetFileName }
                    .map { target -> target to caller.fileName }
            }
            .toMap()

        /** Every confirmed finding in [fileFacts]: a job with BOTH an untrusted checkout and direct secret usage, where the risky trigger is either this file's own, or propagated from a risky-triggered caller via a reusable-workflow call reaching THIS file. */
        fun findingsFor(fileFacts: WorkflowFileFacts): List<PwnRequestFinding> {
            val riskOriginatesHere = fileFacts.riskyTriggers.isNotEmpty()
            val propagatingCaller = propagatedFromCaller[fileFacts.fileName]
            if (!riskOriginatesHere && propagatingCaller == null) return emptyList()

            return fileFacts.jobs.mapNotNull { job ->
                if (job.hasUntrustedCheckout && job.hasDirectSecretUsage) {
                    PwnRequestFinding(job, propagatedFromCallerFile = if (!riskOriginatesHere) propagatingCaller else null)
                } else {
                    null
                }
            }
        }
    }

    fun indexFor(project: Project): PwnRequestIndex {
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            { CachedValueProvider.Result.create(computeIndex(project), PsiModificationTracker.MODIFICATION_COUNT) },
            false,
        )
    }

    private fun computeIndex(project: Project): PwnRequestIndex {
        val scope = GlobalSearchScope.projectScope(project)
        // .path.replace: VirtualFile.path is USUALLY forward-slash-normalized
        // regardless of OS, but a light test fixture's temp files on Windows
        // have shown real backslashes in .path here -- confirmed the hard
        // way via this plugin's own cross-file test failing silently (zero
        // files ever passed this filter, so the whole-project graph was
        // always empty). Same normalization PwnRequestInspection already
        // applies to its own path check.
        val files = (FilenameIndex.getAllFilesByExt(project, "yml", scope) + FilenameIndex.getAllFilesByExt(project, "yaml", scope))
            .filter { it.path.replace('\\', '/').contains("/.github/workflows/") }
        if (files.size > MAX_WORKFLOW_FILES) return PwnRequestIndex(emptyMap())

        val psiManager = PsiManager.getInstance(project)
        val byName = mutableMapOf<String, WorkflowFileFacts>()
        for (virtualFile in files) {
            val text = psiManager.findFile(virtualFile)?.text ?: continue
            if (text.length > MAX_FILE_LENGTH) continue
            byName[virtualFile.name] = WorkflowFileParser.parseFile(text, virtualFile.path, virtualFile.name)
        }
        return PwnRequestIndex(byName)
    }
}
