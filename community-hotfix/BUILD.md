# Build and test from source

Upstream: https://github.com/EMCECS/ecs-sync

- Base commit: `4fbfe8422c0082d8e92c775a03ba4060aa201563` (v3.5.5).
- Reviewed development commit: `466e06d2920ed1ab9814cce9f008812e19b5bc9a`.
- Reviewed engine source tree: `098d2e7a5bb4a5dce3e4738cebd3a6d57b30bc2e`.
- Expected publication source tree after applying the patch: `ede2386d17f9090075b02f6001fc5ef101af1afd`.

The reviewed development commit is provenance information. It is not assumed to be fetchable from the upstream repository. The source archive and clean patch provide that source with file-level modification notices and a modification summary added for publication. These additions change comments and documentation only. The original development history is not included.

## Obtain the source

The corrected code and community documentation are also available on the fork branch:

```bash
git clone --single-branch --branch community/3.5.5-hotfix-1 \
  https://github.com/cody-petry/ecs-sync.git ecs-sync-community-src
cd ecs-sync-community-src
```

For an independently checked source snapshot or to apply the changes to upstream yourself, use the package method below instead.

Either extract `source/ecs-sync-3.5.5-combined-source.tar.gz`, which creates `ecs-sync-3.5.5-combined/`, or apply the patch to a new upstream clone:

```bash
# Set this to the absolute path of the extracted community package.
PACKAGE_DIR=/absolute/path/to/ecs-sync-3.5.5-community-hotfix-20261008

git clone https://github.com/EMCECS/ecs-sync.git ecs-sync-community-src
cd ecs-sync-community-src
git checkout --detach 4fbfe8422c0082d8e92c775a03ba4060aa201563
git apply --check "$PACKAGE_DIR/source/ecs-sync-3.5.5-combined.patch"
git apply --index "$PACKAGE_DIR/source/ecs-sync-3.5.5-combined.patch"
git write-tree
# Expected: ede2386d17f9090075b02f6001fc5ef101af1afd
```

Use a clean clone of the pinned base. The combined patch already includes the original current-version correction. Do not first apply the old patch and then apply this complete diff.

## Build the JAR

The supplied binary was produced using JDK 8 and the checked-in Gradle 7.6.4 wrapper. Use a non-root build account and an installed UTF-8 locale. The wrapper and dependency repositories must be reachable on the first build, including Maven Central, the Gradle plugin repositories, and the Grails repository used during project configuration. A populated local cache can be used with `--offline`.

From the source root:

```bash
export JAVA_HOME=/absolute/path/to/jdk8
export PATH="$JAVA_HOME/bin:$PATH"
export LANG=C.UTF-8 LC_ALL=C.UTF-8
unset JAVA_TOOL_OPTIONS

java -version
javac -version
./gradlew --no-daemon --max-workers=2 clean :ecs-sync-cli:shadowJar
sha256sum ecs-sync-cli/build/libs/ecs-sync-3.5.5.jar
```

The output is `ecs-sync-cli/build/libs/ecs-sync-3.5.5.jar`. Preserve the upstream JAR's licenses and notices when distributing it. Give any newly built binary a distinct filename and its own checksum.

The published checksum identifies the supplied artifact. It is not a guarantee that a new ZIP/JAR build will have the same whole-file checksum. ZIP metadata and generated plugin metadata can differ. The preparation review verified the supplied artifact and source patch; it did not perform a new independent full build of the final combined source.

## Run tests and inspect their results

The affected S3 module can be run with:

```bash
./gradlew --no-daemon --max-workers=2 \
  :storage-plugins:s3-storage:cleanTest \
  :storage-plugins:s3-storage:test
```

The earlier core retry/accounting regression tests can be run with:

```bash
./gradlew --no-daemon --max-workers=2 \
  :ecs-sync-core:cleanTest :ecs-sync-core:test \
  --tests 'com.emc.ecs.sync.RetryCompletionTest' \
  --tests 'com.emc.ecs.sync.InFlightAccountingTest'
```

**Read the JUnit XML, not just Gradle's exit status.** The upstream convention in `buildSrc/src/main/groovy/java-module.gradle` sets `ignoreFailures = true`.

Results appear under each module's `build/test-results/test/` and `build/reports/tests/test/`. Endpoint-dependent tests remain skipped unless their required test configuration is supplied. The final combined S3 result supplied with this build was 46 passed, 59 skipped, and zero failures/errors; `VALIDATION.md` describes the evidence and limits.
