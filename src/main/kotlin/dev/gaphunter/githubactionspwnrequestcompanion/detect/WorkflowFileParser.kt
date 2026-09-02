package dev.gaphunter.githubactionspwnrequestcompanion.detect

import dev.gaphunter.githubactionspwnrequestcompanion.model.JobFacts
import dev.gaphunter.githubactionspwnrequestcompanion.model.WorkflowFileFacts

/**
 * Parses one workflow YAML file (under `.github/workflows/`) text into
 * [WorkflowFileFacts] -- risky triggers (`pull_request_target`,
 * `workflow_run`) and, per job, whether it does an untrusted checkout
 * of the PR/workflow_run head, uses secrets directly, or calls a LOCAL
 * reusable workflow with secrets reachable through that call.
 */
object WorkflowFileParser {

    private val RISKY_TRIGGERS = setOf("pull_request_target", "workflow_run")
    private val UNTRUSTED_CHECKOUT = Regex("""uses:\s*actions/checkout@""")
    private val UNTRUSTED_REF = Regex("""ref:\s*.*(pull_request(_target)?\.head|workflow_run\.head_(sha|branch))""")
    private val SECRET_USAGE = Regex("""\$\{\{\s*secrets\.""")
    private val LOCAL_REUSABLE_WORKFLOW = Regex("""uses:\s*(\./\.github/workflows/([\w.\-]+\.ya?ml))(@[\w.\-/]+)?\s*$""")
    private val SECRETS_INHERIT = Regex("""^\s*secrets:\s*inherit\s*$""")

    fun parseFile(text: String, filePath: String, fileName: String): WorkflowFileFacts {
        val lines = text.lines()
        val riskyTriggers = detectRiskyTriggers(lines)

        // `jobs:` itself must be a real top-level line for findBlockBody's
        // path lookup to have found ANY body at all -- re-finding it here to
        // convert each job's body-relative line index back into a real,
        // whole-file line number (0-based) for the inspection to anchor on.
        val jobsLineIndex = lines.indexOfFirst { Regex("""^jobs:\s*$""").matches(it) }
        val jobsBody = WorkflowYamlBlocks.findBlockBody(lines, listOf("jobs")).orEmpty()
        val jobs = WorkflowYamlBlocks.splitMapEntries(jobsBody).map { entry ->
            val absoluteLineNumber = if (jobsLineIndex >= 0) jobsLineIndex + 1 + entry.lineIndexInScope else entry.lineIndexInScope
            parseJob(entry.key, entry.bodyLines, absoluteLineNumber)
        }

        return WorkflowFileFacts(filePath, fileName, riskyTriggers, jobs)
    }

    private fun parseJob(jobName: String, body: List<String>, lineNumber: Int): JobFacts {
        val hasUntrustedCheckout = hasUntrustedCheckout(body)
        val hasDirectSecretUsage = body.any { SECRET_USAGE.containsMatchIn(it) }

        val usesLine = body.firstOrNull { LOCAL_REUSABLE_WORKFLOW.containsMatchIn(it) }
        val targetFileName = usesLine?.let { LOCAL_REUSABLE_WORKFLOW.find(it)?.groupValues?.get(2) }
        val inheritsSecrets = body.any { SECRETS_INHERIT.matches(it) }
        val explicitSecretsBody = WorkflowYamlBlocks.findBlockBody(body, listOf("secrets"))
        val hasExplicitSecrets = !explicitSecretsBody.isNullOrEmpty()

        return JobFacts(
            jobName = jobName,
            lineNumber = lineNumber,
            hasUntrustedCheckout = hasUntrustedCheckout,
            hasDirectSecretUsage = hasDirectSecretUsage,
            reusableCallTargetFileName = targetFileName,
            reusableCallSecretsReachable = inheritsSecrets || hasExplicitSecrets,
        )
    }

    private fun hasUntrustedCheckout(jobBody: List<String>): Boolean {
        val stepsBody = WorkflowYamlBlocks.findBlockBody(jobBody, listOf("steps")) ?: return false
        val steps = WorkflowYamlBlocks.splitListItems(stepsBody)
        return steps.any { step ->
            step.any { UNTRUSTED_CHECKOUT.containsMatchIn(it) } && step.any { UNTRUSTED_REF.containsMatchIn(it) }
        }
    }

    private fun detectRiskyTriggers(lines: List<String>): Set<String> {
        val onLineIndex = lines.indexOfFirst { it.startsWith("on:") }
        if (onLineIndex < 0) return emptySet()
        val rest = lines[onLineIndex].removePrefix("on:").trim()

        return when {
            rest.isEmpty() -> {
                val body = WorkflowYamlBlocks.findBlockBody(lines, listOf("on")) ?: return emptySet()
                WorkflowYamlBlocks.splitMapEntries(body).map { it.key }.filter { it in RISKY_TRIGGERS }.toSet()
            }
            rest.startsWith("[") -> rest.trim('[', ']').split(",").map { it.trim().trim('"', '\'') }.filter { it in RISKY_TRIGGERS }.toSet()
            else -> setOf(rest.trim('"', '\'')).filter { it in RISKY_TRIGGERS }.toSet()
        }
    }
}
