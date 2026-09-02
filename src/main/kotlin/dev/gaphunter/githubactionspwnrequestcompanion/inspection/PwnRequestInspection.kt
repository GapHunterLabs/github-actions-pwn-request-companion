package dev.gaphunter.githubactionspwnrequestcompanion.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.gaphunter.githubactionspwnrequestcompanion.detect.ProjectPwnRequestGraphBuilder
import dev.gaphunter.githubactionspwnrequestcompanion.detect.WorkflowFileParser
import dev.gaphunter.githubactionspwnrequestcompanion.model.PwnRequestFinding
import dev.gaphunter.githubactionspwnrequestcompanion.review.ReviewPrompt

/**
 * Flags a GitHub Actions job triggered by `pull_request_target`/
 * `workflow_run` that checks out the PR/workflow_run head AND has
 * secrets reachable -- directly, or through one hop of a local
 * reusable-workflow call -- the real "Pwn Request" pattern GitHub
 * Security Lab documents. See [ProjectPwnRequestGraphBuilder] for the
 * whole-project reusable-workflow call graph.
 *
 * Only re-parses the CURRENT file for its own facts (the file being
 * highlighted IS the one anchored on); the cross-file reusable-
 * workflow target lookup comes from the cached, whole-project index.
 */
class PwnRequestInspection : LocalInspectionTool() {

    companion object {
        const val MAX_FILE_LENGTH = 500_000
        private val WORKFLOW_FILE_PATH = Regex(""".*/\.github/workflows/[^/]+\.ya?ml$""", RegexOption.IGNORE_CASE)
    }

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val virtualFile = file.virtualFile ?: return null
        if (!WORKFLOW_FILE_PATH.matches(virtualFile.path.replace('\\', '/'))) return null

        val text = file.text
        if (text.length > MAX_FILE_LENGTH) return null

        // No early-return on an empty fileFacts.riskyTriggers here -- unlike
        // this catalog's other whole-project inspections, a risky trigger
        // can be PROPAGATED from a different file (a reusable workflow's own
        // `on: workflow_call` is never itself risky); index.findingsFor
        // already checks both the local and propagated case, see there.
        val fileFacts = WorkflowFileParser.parseFile(text, virtualFile.path, virtualFile.name)
        val index = ProjectPwnRequestGraphBuilder.indexFor(file.project)
        val findings = index.findingsFor(fileFacts)
        if (findings.isEmpty()) return null

        val document = file.viewProvider.document ?: return null
        val problems = mutableListOf<ProblemDescriptor>()

        for (finding in findings) {
            val lineNumber = finding.job.lineNumber
            if (lineNumber !in 0 until document.lineCount) continue
            val lineStartOffset = document.getLineStartOffset(lineNumber)
            val lineEndOffset = document.getLineEndOffset(lineNumber)
            val anchor = leafElementAt(file, lineStartOffset) ?: continue
            val anchorStart = anchor.textRange.startOffset
            val relativeRange = TextRange(
                (lineStartOffset - anchorStart).coerceAtLeast(0),
                (lineEndOffset - anchorStart).coerceAtMost(anchor.textLength),
            )
            if (relativeRange.startOffset >= relativeRange.endOffset) continue

            problems += manager.createProblemDescriptor(
                anchor,
                relativeRange,
                messageFor(finding),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
            )
            ReviewPrompt.recordHit(file.project, "${virtualFile.path}:${finding.job.jobName}")
        }

        return if (problems.isEmpty()) null else problems.toTypedArray()
    }

    private fun messageFor(finding: PwnRequestFinding): String {
        val triggerSource = if (finding.propagatedFromCallerFile != null) {
            "propagated from '${finding.propagatedFromCallerFile}', which calls this reusable workflow with secrets reachable"
        } else {
            "this workflow's own pull_request_target/workflow_run trigger"
        }
        return "Job '${finding.job.jobName}' checks out untrusted PR/workflow_run head content AND uses secrets in the same job " +
            "($triggerSource) -- the real \"Pwn Request\" pattern (GitHub Security Lab)"
    }

    private fun leafElementAt(file: PsiFile, startOffset: Int): PsiElement? {
        if (startOffset < 0 || startOffset >= file.textLength) return null
        var element = file.findElementAt(startOffset) ?: return file
        while (element.firstChild != null) {
            element = element.firstChild
        }
        return element
    }
}
