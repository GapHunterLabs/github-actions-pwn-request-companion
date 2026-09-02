package dev.gaphunter.githubactionspwnrequestcompanion.model

/**
 * One `jobs.<job_id>` entry of a GitHub Actions workflow file --
 * either an ordinary job (with `steps:`) or a reusable-workflow call
 * (`uses: ./.github/workflows/x.yml`).
 */
data class JobFacts(
    val jobName: String,
    /** File-local line number of this job's own key line, the anchor for a warning. */
    val lineNumber: Int,
    /** This job's OWN steps do `actions/checkout` with a `ref:` naming the PR/workflow_run head -- the exact "pwn request" checkout shape. */
    val hasUntrustedCheckout: Boolean,
    /** This job's OWN steps reference `${{ secrets.* }}` directly -- secrets already reachable with zero graph hops. */
    val hasDirectSecretUsage: Boolean,
    /** Filename only (e.g. `deploy.yml`) of a LOCAL reusable workflow this job calls via `uses: ./.github/workflows/<file>` -- null when this job isn't a reusable-workflow call. */
    val reusableCallTargetFileName: String?,
    /** True when secrets are reachable through the reusable-workflow call (`secrets: inherit`, or an explicit non-empty `secrets:` map) -- meaningless when [reusableCallTargetFileName] is null. */
    val reusableCallSecretsReachable: Boolean,
)

/** Everything this plugin's parser extracted from one workflow YAML file. */
data class WorkflowFileFacts(
    val filePath: String,
    val fileName: String,
    /** The subset of `on:` triggers that are risky (`pull_request_target`, `workflow_run`) -- empty when the workflow has neither. */
    val riskyTriggers: Set<String>,
    val jobs: List<JobFacts>,
)

/**
 * One confirmed "pwn request" finding: [job] has BOTH an untrusted
 * checkout and direct secret usage in its own steps. [propagatedFromCallerFile]
 * is null when [job]'s own workflow file has the risky trigger itself
 * (the direct case); otherwise it names the OTHER file whose
 * risky-triggered job called this one's workflow (via a local
 * reusable-workflow call with secrets reachable) -- the cross-file
 * propagation case.
 */
data class PwnRequestFinding(val job: JobFacts, val propagatedFromCallerFile: String?)
