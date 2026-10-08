# Fixes in the community test build

This is an **unofficial community test build** of ecs-sync 3.5.5. It is not an upstream release, and maintainer approval or support has not been obtained. The numbers below identify this patch series, not GitHub issue numbers.

Base commit: `4fbfe8422c0082d8e92c775a03ba4060aa201563` (v3.5.5).

Reviewed build source commit: `466e06d2920ed1ab9814cce9f008812e19b5bc9a`.

Publication branch: [community/3.5.5-hotfix-1](https://github.com/cody-petry/ecs-sync/tree/community/3.5.5-hotfix-1). Publication adds modification notices and documentation; executable source is unchanged.

| Change | Problem addressed | Resulting behavior |
| --- | --- | --- |
| Earlier `islatest-v2` correction, included | Timestamp/version-ID sorting could select an older data version as current when the actual current version was a delete marker. Reusing the resulting stream could produce `Stream is closed`. | Honors `IsLatest` when identifying the current entry and checks the selected entry. Applies to both S3 plugins. |
| 0001: retry completion | A job could finish while an object was still waiting to be retried, leaving work unprocessed. | Tracks outstanding objects through retry scheduling and includes that accounting in completion checks. |
| 0002: AWS version-delete batching | Replacing a destination history could send more than 1,000 entries in one multi-object delete request. | Splits the deletion into batches of at most 1,000 explicit key/version-ID pairs. An unsuccessful batch stops processing rather than continuing replay. |
| 0003: pagination, completed | An empty but truncated listing page could prematurely end enumeration even though a later page contained objects. | Both plugins' live-object and deleted-key iterators continue through empty truncated pages. Missing or unchanged continuation markers on those empty pages cause an error instead of a silent stop or restart loop. Includes the later correction to the AWS live-object iterator. |
| 0004: AWS deleted-key exclusions | `excludedKeys` was applied to live objects but not consistently to keys whose current version was a delete marker. | Applies the same exclusion matching to those deleted keys. |
| 0005: version-chain order | Older versions with identical listing timestamps were sorted by opaque version-ID strings. Those IDs do not establish history order and differ between source and destination. | Uses the provider's version-listing sequence, reversed into oldest-to-newest order for replay and verification, while retaining current-version handling. Applies to both S3 plugins. |

## What the MD5 ordering fix changes

With version copying enabled, the verification checksum incorporates the ordered version history. A mismatch can therefore describe either of two situations:

- The stored history is correct, but the old comparator calculates the source and destination checksums in different orders.
- The old comparator orders the source history incorrectly before replay, so the destination receives an incorrect historical sequence even when the individual object bytes remain intact.

0005 addresses both ordering paths. It does not disable verification, suppress MD5 errors, or repair arbitrary content corruption. It also does not retroactively repair an existing destination without a further copy operation. A fresh destination makes the first test easier to interpret.

The problem becomes easier to encounter when a provider exposes whole-second `LastModified` values, including the reported Ceph Tentacle environment. It can also occur when destination replay creates timestamp ties even if the original source history had distinct timestamps.

## Boundaries

- 0005 requires a provider that lists each key's versions and delete markers together in storage order, newest first, through pagination. The patched code warns and moves the current entry if `IsLatest` is not first; this does not establish the correct order of all older entries on a nonconforming provider. Do not infer compatibility with every S3-compatible service from the Ceph test.
- 0001's regression evidence covers the reproduced retry-completion problem and selected accounting paths. It does not prove that every forced-termination or same-instance restart path leaves a zero counter.
- This series does not update bundled dependencies, change the database schema, or provide an application-level backup/restore compatibility certification.

See [VALIDATION.md](VALIDATION.md) for the exact test scope and the first operator-reported real-storage result.
