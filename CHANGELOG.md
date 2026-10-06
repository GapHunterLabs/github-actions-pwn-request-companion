<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# GitHub Actions Pwn-Request Companion Changelog

## [Unreleased]

### Added

- A description page for the inspection in **Settings | Editor |
  Inspections**, which showed "Under construction".

### Changed

- The rating prompt's local counter keeps one-way fingerprints of findings
  instead of their file paths, and deletes the list that earlier versions
  kept.
- `PRIVACY.md` describes the values the plugin keeps in the IDE's local
  settings.

## [0.1.1]

### Fixed

- Review/star CTA now links to this plugin's own Marketplace
  reviews page instead of the vendor's generic plugin list.

## [0.1.0]

### Added

- Real reusable-workflow call graph across every
  `.github/workflows/*.yml` file, flagging a `pull_request_target`/
  `workflow_run` job that checks out untrusted head content while
  secrets are reachable directly or through one hop of a local
  reusable-workflow call.

[Unreleased]: https://github.com/GapHunterLabs/github-actions-pwn-request-companion/compare/0.1.1...HEAD
[0.1.1]: https://github.com/GapHunterLabs/github-actions-pwn-request-companion/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/GapHunterLabs/github-actions-pwn-request-companion/commits/0.1.0
