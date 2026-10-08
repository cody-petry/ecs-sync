# Validation record

Status as of October 8, 2026: **unofficial community test build with offline regression evidence and one operator-reported copy completed with zero errors**. This is not maintainer approval or a supported upstream release.

## Artifact identity

| Item | Value |
| --- | --- |
| Upstream base | v3.5.5, commit `4fbfe8422c0082d8e92c775a03ba4060aa201563` |
| Reviewed build source | `466e06d2920ed1ab9814cce9f008812e19b5bc9a` |
| Included changes | Earlier `islatest-v2` correction plus 0001–0005, including the completed 0003 pagination fix |
| JAR | `ecs-sync-3.5.5-v2+fix0001-0005-COMBINED.jar` |
| Size | 53,273,184 bytes |
| SHA-256 | `49f11d693e066dd7018ea8f3acbfd79e7525f6ba3cc63a41bba2f696a5c8b943` |

The JAR still prints `EcsSync v3.5.5`. Use the SHA-256 to identify this build. The supplied split parts were independently reassembled and the whole-JAR hash, archive integrity, and version smoke test checked. The review did not independently rebuild the final combined source. Publication adds file-level change notices and a modification summary to that reviewed source; executable code and the supplied JAR are unchanged.

## Repository test evidence

The build producer ran the tests with the project's Gradle 7.6.4 wrapper, OpenJDK 8, a non-root account, and a UTF-8 locale. The counts below were checked against the supplied raw JUnit XML, rather than inferred from Gradle's success message. The project permits test tasks to finish despite test failures.

**Final combined S3 module:** 105 tests recorded, **46 passed, zero failures/errors, 59 skipped**. The skipped tests require configured live endpoints and were not executed against Ceph in this test run.

| Focused test class | Final result | Relevant fail-before evidence |
| --- | --- | --- |
| `VersionChainCurrentVersionTest` | 5 passed | All 5 failed on plain v3.5.5 before the earlier current-version correction. |
| `DeleteBatchingTest` | 7 passed | 3 of 7 failed without 0002. |
| `ListingPaginationTest` | 18 passed | 7 of 18 failed on the tests-only commit before the final 0003 completion. |
| `ExcludedKeysTest` | 4 passed | 2 of 4 failed without 0004. |
| `VersionChainOrderTest` | 9 passed | 6 of 9 failed without 0005. |

These tests exercise real plugin code with in-memory SDK fixtures. The ordering cases cover same-second older versions, interleaved data versions and delete markers, histories spanning pages, replay, aggregate checksum comparison, and an unchanged second run. They are not live-provider integration tests.

Earlier core evidence for 0001 contains 4 passing `RetryCompletionTest` cases and 7 passing `InFlightAccountingTest` cases. One of the four retry-completion cases failed before the fix. The core production change was retained in the combined tree. Shutdown coverage has limits: the termination assertions do not establish universally balanced zero accounting after every forced-stop path.

An earlier whole-project candidate result recorded 286 tests: 181 passed, zero failures/errors, and 105 skipped. That result predates 0005 and the final pagination completion and includes a core rerun after an intermittent `ParallelInputStreamTest` failure. It must not be presented as a fresh full-project pass for the final combined commit. The final combined run described above covers the S3 module.

## Local end-to-end and real-storage evidence

The supplied local mock rehearsal ran the actual combined JAR through copying and verification and reported success for the selected data-only test dataset. A separate non-storage-order mock dataset produced a failure, documenting a provider-ordering limitation. Mock results do not establish Ceph behavior.

On October 8, the operator reported that a copy after installation of the combined JAR **finished with zero errors**. The surrounding deployment record confirms the installed JAR checksum and service restart. The reported environment uses Rocky Linux and Ceph Tentacle. The operator's normal copy workflow is between independent Ceph clusters, with the source backup repository in maintenance mode.

The completion evidence is an operator message, not a supplied full job export. The screenshot does not independently establish that run's exact object/version counts, all effective job options, or its complete log history. It is a positive initial result, not proof that every previous failure has been diagnosed or every fix exercised on real storage.

## Validation still outstanding

- An independent source/destination comparison of per-key version counts, interleaved data/delete-marker order, content, and current state for the completed real copy.
- An application-level recovery or restore test of the copied backup repository.
- Broader endpoint and workload coverage, including providers other than the reported Ceph environment.

Preserve the source and test destination while checking the result. A zero-error ecs-sync completion is meaningful evidence, but does not by itself establish all of the outstanding items above.
