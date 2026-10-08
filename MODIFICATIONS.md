# Modification notice

This unofficial ecs-sync **v3.5.5-community.1** distribution includes changes
made on October 7 and October 8, 2026. It is based on upstream v3.5.5, commit
`4fbfe8422c0082d8e92c775a03ba4060aa201563`.

The compiled community JAR was built from reviewed commit
`466e06d2920ed1ab9814cce9f008812e19b5bc9a`. The publication source adds a prominent
community-modification or community-addition notice inside each changed or
added Java file's existing license header and includes this document. These publication changes only affect
comments and documentation. Java source line counts and executable code are
unchanged. The JAR has not been rebuilt for these notices.

Changes include:

- Correct current-version selection using `IsLatest` (the original islatest-v2 fix).
- Keep pending retries accounted for until terminal completion (0001).
- Batch AWS multi-object version deletes into requests of at most 1,000 entries (0002).
- Continue listing through empty truncated pages and detect a missing or stalled
  continuation marker in all four affected bucket iterators (completed 0003).
- Apply AWS excluded-key rules to currently deleted keys as well (0004).
- Use storage listing order to reconstruct version histories for replay and
  verification, rather than opaque version-ID timestamp tie breaking (0005).
- Add offline regression tests and guard a test teardown when endpoint setup
  was skipped.

Upstream copyright, license, and attribution notices are retained. The twelve
new regression-test and fixture files retain their supplied license headers;
the new file-level notices identify them as community additions without
asserting or changing copyright ownership. This is a
community modification, not an official Dell release or endorsement. The
following 21 Java files are changed or added relative to upstream v3.5.5:

- `ecs-sync-core/src/main/java/com/emc/ecs/sync/EcsSync.java`
- `ecs-sync-core/src/main/java/com/emc/ecs/sync/SyncStats.java`
- `ecs-sync-core/src/main/java/com/emc/ecs/sync/SyncTask.java`
- `ecs-sync-core/src/main/java/com/emc/ecs/sync/util/EnhancedThreadPoolExecutor.java`
- `ecs-sync-core/src/test/java/com/emc/ecs/sync/InFlightAccountingTest.java`
- `ecs-sync-core/src/test/java/com/emc/ecs/sync/RetryCompletionTest.java`
- `ecs-sync-core/src/test/java/com/emc/ecs/sync/test/GatedThrottle.java`
- `ecs-sync-core/src/test/java/com/emc/ecs/sync/test/InjectedFailureFilter.java`
- `storage-plugins/s3-storage/src/main/java/com/emc/ecs/sync/storage/s3/AbstractS3Storage.java`
- `storage-plugins/s3-storage/src/main/java/com/emc/ecs/sync/storage/s3/AwsS3Storage.java`
- `storage-plugins/s3-storage/src/main/java/com/emc/ecs/sync/storage/s3/EcsS3Storage.java`
- `storage-plugins/s3-storage/src/main/java/com/emc/ecs/sync/storage/s3/S3VersionComparator.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/AwsS3LargeFileUploaderTest.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/s3/DeleteBatchingTest.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/s3/ExcludedKeysTest.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/s3/InMemoryAmazonS3.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/s3/InMemoryEcsS3Client.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/s3/ListingPaginationTest.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/s3/S3PluginTestSupport.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/s3/VersionChainCurrentVersionTest.java`
- `storage-plugins/s3-storage/src/test/java/com/emc/ecs/sync/storage/s3/VersionChainOrderTest.java`
