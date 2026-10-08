# ecs-sync 3.5.5 Community Hotfix 1

This branch contains community corrections to upstream ecs-sync v3.5.5. It is an unofficial test build, with release label `v3.5.5-community.1`. It is not an EMCECS release or a maintainer-approved build.

## Use this build

The corrected source and tests are available on this branch. The compiled release assets have not yet been published. Check the [fork's Releases page](https://github.com/cody-petry/ecs-sync/releases) for binary availability.

- Existing ecs-sync 3.5.5 installations: follow [INSTALL.md](INSTALL.md) for the engine JAR replacement, readiness checks, controlled test, and rollback.
- New installations: install the [official v3.5.5 distribution](https://github.com/EMCECS/ecs-sync/releases/tag/v3.5.5), then substitute the community engine JAR using the documented procedure. This release does not supply a new system installer or web UI.
- Developers: see [BUILD.md](BUILD.md) to build from this branch or apply the complete patch from the release package to a clean upstream tree.

## Included corrections

The earlier current-version fix from [upstream issue #121](https://github.com/EMCECS/ecs-sync/issues/121) is included. Five further corrections address retry completion, AWS version-delete batching, empty truncated pages, deleted-key exclusions, and historical version ordering for replay and verification. [FIXES.md](FIXES.md) explains each change and its limits.

The version-ordering change preserves the provider's mixed listing sequence across pages. It requires newest-first provider listing order and keeps verification enabled. It addresses wrong historical replay and checksums calculated in inconsistent version order.

## Binary identity and validation

File: `ecs-sync-3.5.5-v2+fix0001-0005-COMBINED.jar`, 53,273,184 bytes.

SHA-256:

```text
49f11d693e066dd7018ea8f3acbfd79e7525f6ba3cc63a41bba2f696a5c8b943
```

This is the unchanged JAR installed for the operator-reported copy that finished with zero errors. It still prints `EcsSync v3.5.5`; use its filename and checksum to identify it. Independent per-version comparison and an application-level restore test remain outstanding.

The final supplied S3 module run records 46 passed, 59 endpoint-gated skips, and zero failures. See [VALIDATION.md](VALIDATION.md) for fail-before evidence, earlier core results, and coverage limits.

## Source and attribution

The upstream base is `4fbfe8422c0082d8e92c775a03ba4060aa201563`. The reviewed engine source is unchanged apart from file-level modification notices and a modification summary added for publication. Java source line counts were preserved; the JAR was not rebuilt. [SOURCE-PROVENANCE.txt](SOURCE-PROVENANCE.txt) records both source tree identities.

The fork additionally includes this documentation and an introduction in the root README. The source-only patch/archive distributed with the release excludes those extra fork documents.

The upstream [LICENSE](../LICENSE), [NOTICE](../NOTICE), and original attribution are retained. [MODIFICATIONS.md](../MODIFICATIONS.md) lists the affected files. The changes were developed with AI assistance and reviewed against source and regression evidence.
