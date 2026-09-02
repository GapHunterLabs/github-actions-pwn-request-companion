# GitHub Actions Pwn-Request Companion

Flags a job that checks out untrusted PR/workflow_run head content
while secrets are reachable -- the real "Pwn Request" pattern.

## Why it exists

GitHub Security Lab's own documented failure pattern ("Keeping your
GitHub Actions and workflows secure: Preventing pwn requests"): a
workflow triggered by `pull_request_target`/`workflow_run` is
privileged (repository write access, secrets) by design, but if it
also checks out the PR's own head content before any privileged step,
an attacker's code runs with that privilege -- the mechanism behind
multiple real, publicly documented supply-chain incidents. zizmor (used
internally by this catalog's own pipeline) is a CLI tool, not an IDE
plugin; no dedicated Marketplace plugin found for this exact,
cross-file reusable-workflow angle.

## Why built this way

- **A real reusable-workflow call graph**, not a single-file check: a
  workflow's own steps might have zero secret usage while its
  `secrets: inherit` call to a LOCAL reusable workflow (a different
  file) is exactly where the real secret usage lives -- resolving that
  requires cross-referencing every `.github/workflows/*.yml` file in
  the project.
- **Not a real YAML parser** -- an indentation-based micro-scanner,
  extended with a real "split every key of this mapping block"
  operation (`WorkflowYamlBlocks.splitMapEntries`) that this catalog's
  other YAML scanners never needed (job names are an unknown, arbitrary
  set, not a fixed key path).
- **Cached per-project**, invalidated on any PSI change.

## v0.1 scope — stated honestly, not exhaustively

- Only `uses: ./.github/workflows/x.yml` (a local reusable workflow in
  the SAME repo) -- never `owner/repo/.github/workflows/x.yml@ref` (an
  external repo, out of this project's own reach).
- Exactly one hop of reusable-workflow resolution -- a chain of two or
  more reusable workflows calling each other isn't followed.
- A fixed list of risky triggers (`pull_request_target`,
  `workflow_run`) and a fixed textual pattern for the untrusted-checkout
  `ref:` (`pull_request(_target).head`,
  `workflow_run.head_(sha|branch)`) -- never follows a `ref:` value
  built from an intermediate expression/variable.
- A project with more than 200 workflow files skips whole-project
  analysis entirely rather than risk hanging the IDE.

## Usage

Open a workflow file under `.github/workflows/` triggered by
`pull_request_target`/`workflow_run` that checks out the PR head and
has secrets reachable -- the job's line shows a warning.

## Enterprise / Team Licensing

Need enterprise features, custom rules, or team licensing? Contact us at
**gaphunterlabs@gmail.com**.

## Development

```
./gradlew test           # unit tests
./gradlew buildPlugin    # generates build/distributions/*.zip
./gradlew verifyPlugin   # checks compatibility against real IDEs
```

## License

Apache-2.0. See `LICENSE`.
