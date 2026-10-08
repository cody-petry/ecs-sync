# ecs-sync 3.5.5 Community Hotfix 1

Tag: `v3.5.5-community.1`. Prepared October 8, 2026.

**Unofficial community pre-release for testing.** Related upstream report: [EMCECS/ecs-sync #121](https://github.com/EMCECS/ecs-sync/issues/121).

This build includes the earlier current-version correction plus fixes 0001–0005: retry-completion accounting, AWS version-delete batching, completed empty-page pagination handling, deleted-key exclusions, and provider-order version replay/verification.

The ordering change addresses both incorrect historical replay and false aggregate MD5 mismatches caused by timestamp/version-ID sorting. It does not disable verification. It requires newest-first provider listing order for interleaved versions and delete markers.

An operator reported a copy after installation finished with zero errors. Independent per-version validation and an application-level restore test are still outstanding. The final supplied S3 test run recorded 46 passed, 59 skipped, and zero failures. See the package's `VALIDATION.md` for exact scope and limitations.

## Binary

`ecs-sync-3.5.5-v2+fix0001-0005-COMBINED.jar`, 53,273,184 bytes.

```text
49f11d693e066dd7018ea8f3acbfd79e7525f6ba3cc63a41bba2f696a5c8b943
```

The JAR still reports version 3.5.5. Identify this build by its checksum. The supplied artifact was built with JDK 8; the reported host installation uses Rocky Linux.

## Source and installation

Upstream base: `4fbfe8422c0082d8e92c775a03ba4060aa201563`.

Reviewed engine source tree: `098d2e7a5bb4a5dce3e4738cebd3a6d57b30bc2e`.

Publication source tree: `ede2386d17f9090075b02f6001fc5ef101af1afd`. File-level change notices and a modification summary were added for publication; executable source is unchanged. The tested JAR is unchanged.

The package includes a complete source/test patch, exact source archive, build instructions, preserved license notices, and `INSTALL.md` with explicit symlink installation and rollback. Save the previous JAR and test against a fresh destination before relying on this build for your workload.
