package dev.gaphunter.githubactionspwnrequestcompanion.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowFileParserTest {

    @Test
    fun `block-form pull_request_target trigger is detected`() {
        val facts = WorkflowFileParser.parseFile(
            """
            name: CI
            on:
              pull_request_target:
                types: [opened, synchronize]
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                - run: echo hi
            """.trimIndent(),
            "wf.yml",
            "wf.yml",
        )
        assertEquals(setOf("pull_request_target"), facts.riskyTriggers)
    }

    @Test
    fun `inline list trigger form is detected`() {
        val facts = WorkflowFileParser.parseFile(
            """
            name: CI
            on: [push, workflow_run]
            jobs:
              build:
                steps:
                - run: echo hi
            """.trimIndent(),
            "wf.yml",
            "wf.yml",
        )
        assertEquals(setOf("workflow_run"), facts.riskyTriggers)
    }

    @Test
    fun `a safe trigger like push alone produces no risky triggers`() {
        val facts = WorkflowFileParser.parseFile(
            """
            on: push
            jobs:
              build:
                steps:
                - run: echo hi
            """.trimIndent(),
            "wf.yml",
            "wf.yml",
        )
        assertTrue(facts.riskyTriggers.isEmpty())
    }

    @Test
    fun `untrusted checkout of pull_request head sha is detected`() {
        val facts = WorkflowFileParser.parseFile(
            """
            on:
              pull_request_target:
            jobs:
              build:
                steps:
                - uses: actions/checkout@v4
                  with:
                    ref: ${'$'}{{ github.event.pull_request.head.sha }}
                - run: npm test
                  env:
                    TOKEN: ${'$'}{{ secrets.NPM_TOKEN }}
            """.trimIndent(),
            "wf.yml",
            "wf.yml",
        )
        val job = facts.jobs.single()
        assertTrue(job.hasUntrustedCheckout)
        assertTrue(job.hasDirectSecretUsage)
    }

    @Test
    fun `a normal checkout with no ref is not treated as untrusted`() {
        val facts = WorkflowFileParser.parseFile(
            """
            on:
              pull_request_target:
            jobs:
              build:
                steps:
                - uses: actions/checkout@v4
                - run: npm test
                  env:
                    TOKEN: ${'$'}{{ secrets.NPM_TOKEN }}
            """.trimIndent(),
            "wf.yml",
            "wf.yml",
        )
        assertFalse(facts.jobs.single().hasUntrustedCheckout)
    }

    @Test
    fun `reusable workflow call with secrets inherit is parsed`() {
        val facts = WorkflowFileParser.parseFile(
            """
            on:
              pull_request_target:
            jobs:
              build:
                steps:
                - uses: actions/checkout@v4
                  with:
                    ref: ${'$'}{{ github.event.pull_request.head.sha }}
              deploy:
                needs: build
                uses: ./.github/workflows/deploy.yml
                secrets: inherit
            """.trimIndent(),
            "wf.yml",
            "wf.yml",
        )
        val build = facts.jobs.first { it.jobName == "build" }
        assertTrue(build.hasUntrustedCheckout)
        assertFalse(build.hasDirectSecretUsage)
        assertNull(build.reusableCallTargetFileName)

        val deploy = facts.jobs.first { it.jobName == "deploy" }
        assertEquals("deploy.yml", deploy.reusableCallTargetFileName)
        assertTrue(deploy.reusableCallSecretsReachable)
    }

    @Test
    fun `a job with only uses and secrets inherit, no steps, is parsed as a reusable-workflow call`() {
        val facts = WorkflowFileParser.parseFile(
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
        val job = facts.jobs.single()
        assertFalse(job.hasUntrustedCheckout)
        assertEquals("deploy.yml", job.reusableCallTargetFileName)
        assertTrue(job.reusableCallSecretsReachable)
    }

    @Test
    fun `a workflow_call job with its own checkout and secret usage is parsed as both`() {
        val facts = WorkflowFileParser.parseFile(
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
        assertTrue(facts.riskyTriggers.isEmpty())
        val job = facts.jobs.single()
        assertTrue(job.hasUntrustedCheckout)
        assertTrue(job.hasDirectSecretUsage)
    }

    @Test
    fun `job line numbers are real, whole-file line numbers`() {
        val text = """
            name: CI
            on:
              pull_request_target:
            jobs:
              build:
                steps:
                - run: echo hi
        """.trimIndent()
        val facts = WorkflowFileParser.parseFile(text, "wf.yml", "wf.yml")
        val lineNumber = facts.jobs.single().lineNumber
        assertEquals("  build:", text.lines()[lineNumber])
    }
}
