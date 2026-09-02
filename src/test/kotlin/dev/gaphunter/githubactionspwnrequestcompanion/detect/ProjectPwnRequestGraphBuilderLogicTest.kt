package dev.gaphunter.githubactionspwnrequestcompanion.detect

import dev.gaphunter.githubactionspwnrequestcompanion.model.WorkflowFileFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises [ProjectPwnRequestGraphBuilder.PwnRequestIndex]'s own propagation logic directly against hand-built [WorkflowFileFacts] -- no PSI/[com.intellij.psi.search.FilenameIndex] involved, isolating the graph algorithm from the test-fixture/VFS layer. */
class ProjectPwnRequestGraphBuilderLogicTest {

    @Test
    fun `a workflow_call job with checkout plus secrets is flagged when its file is called from a risky-triggered caller`() {
        val ciFacts = WorkflowFileParser.parseFile(
            """
            on:
              pull_request_target:
            jobs:
              call-deploy:
                uses: ./.github/workflows/deploy.yml
                secrets: inherit
            """.trimIndent(),
            "ci.yml",
            "ci.yml",
        )
        val deployFacts = WorkflowFileParser.parseFile(
            """
            on:
              workflow_call:
            jobs:
              deploy:
                steps:
                - uses: actions/checkout@v4
                  with:
                    ref: ${'$'}{{ github.event.pull_request.head.sha }}
                - run: ./deploy.sh
                  env:
                    TOKEN: ${'$'}{{ secrets.DEPLOY_TOKEN }}
            """.trimIndent(),
            "deploy.yml",
            "deploy.yml",
        )

        val index = ProjectPwnRequestGraphBuilder.PwnRequestIndex(mapOf("ci.yml" to ciFacts, "deploy.yml" to deployFacts))
        val findings = index.findingsFor(deployFacts)

        assertEquals(1, findings.size)
        assertEquals("deploy", findings.single().job.jobName)
        assertEquals("ci.yml", findings.single().propagatedFromCallerFile)
    }
}
