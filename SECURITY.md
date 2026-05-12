# Security policy

Spectre is pre-1.0. The HTTP transport in the `server` module is **experimental** and
**unauthenticated**, and is intended for trusted local / test environments only.

The full trust-boundary documentation, capability inventory, and accepted-risk list live on
the published docs site: <https://spectre.sebastiano.dev/SECURITY/>. Please read that page
before relying on Spectre's HTTP transport or screenshot / recording capabilities outside the
intended trusted-local model.

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| 0.x     | :white_check_mark: |

## Reporting a vulnerability

We do not run a bug bounty programme.

- **Current channel:** email **spectre@sebastiano.dev**.
- **Future:** GitHub's private vulnerability reporting flow may also be used once it is
  enabled on this repository.

Please do not file public GitHub issues for security reports.
