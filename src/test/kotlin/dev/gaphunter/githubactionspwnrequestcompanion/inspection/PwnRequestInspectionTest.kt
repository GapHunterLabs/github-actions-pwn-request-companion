package dev.gaphunter.githubactionspwnrequestcompanion.inspection

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Files are placed under `.github/workflows/` in the test project's
 * virtual filesystem -- the inspection's own path filter requires it,
 * matching the real repo layout GitHub Actions itself requires.
 */
class PwnRequestInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(PwnRequestInspection::class.java)
    }

    /** `configureByText` only accepts a flat filename -- a real nested path (`.github/workflows/x.yml`, which the inspection's own path filter requires) needs `addFileToProject` + opening it explicitly as the file under test. */
    private fun configureWorkflowFile(path: String, content: String) {
        val file = myFixture.addFileToProject(path, content).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
    }

    fun `test direct secret usage alongside an untrusted checkout is flagged`() {
        configureWorkflowFile(
            ".github/workflows/ci.yml",
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
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("Pwn Request") == true })
    }

    fun `test a reusable workflow's own job with checkout plus secrets is flagged when called from a risky-triggered caller`() {
        // ci.yml itself never does a checkout -- it only PROPAGATES its
        // pull_request_target trigger's github-event context and secrets
        // into deploy.yml via `uses:` + `secrets: inherit`. GitHub Actions
        // forbids a job from having both `uses:` and `steps:`, so the real
        // checkout+secrets shape can only ever live in the CALLEE's own
        // job -- exactly what this test proves gets flagged.
        myFixture.addFileToProject(
            ".github/workflows/ci.yml",
            """
            on:
              pull_request_target:
            jobs:
              call-deploy:
                uses: ./.github/workflows/deploy.yml
                secrets: inherit
            """.trimIndent(),
        )
        configureWorkflowFile(
            ".github/workflows/deploy.yml",
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
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(
            highlights.any {
                it.description?.contains("Pwn Request") == true &&
                    it.description?.contains("'deploy'") == true &&
                    it.description?.contains("ci.yml") == true
            },
        )
    }

    fun `test a workflow_call-only workflow with checkout plus secrets is NOT flagged when nothing calls it from a risky trigger`() {
        configureWorkflowFile(
            ".github/workflows/deploy.yml",
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
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("Pwn Request") == true })
    }

    fun `test a checkout with no ref is never flagged even with secrets present`() {
        configureWorkflowFile(
            ".github/workflows/safe.yml",
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
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("Pwn Request") == true })
    }

    fun `test a push-triggered workflow is never flagged even with the same shape`() {
        configureWorkflowFile(
            ".github/workflows/push.yml",
            """
            on:
              push:
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
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("Pwn Request") == true })
    }

    fun `test a workflow file outside dot github workflows is never scanned`() {
        myFixture.configureByText(
            "other.yml",
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
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("Pwn Request") == true })
    }
}
