/*
 * Copyright (c) 2016-2022 Dell Inc. or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Community addition, 2026-10-08: regression test/fixture for ecs-sync v3.5.5-community.1; see MODIFICATIONS.md. */
package com.emc.ecs.sync.storage.s3;

import com.emc.ecs.sync.config.RoleType;
import com.emc.ecs.sync.config.SyncOptions;
import com.emc.ecs.sync.model.ObjectMetadata;
import com.emc.ecs.sync.model.SyncObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static com.emc.ecs.sync.storage.s3.S3PluginTestSupport.*;

/**
 * Regression tests for the "IsLatest" fix (islatest-v2 patch): the current version of a key must be the one the storage
 * flags IsLatest, independent of LastModified ties and of the version-id strings. The scenario is the one observed in
 * production: a data version and its delete marker share the same LastModified second and the delete marker's version
 * id sorts before the data version's.
 * <p>
 * Pre-fix (plain v3.5.5): {@link #comparatorSortsCurrentVersionLast}, {@link #awsLoadObjectReturnsTheIsLatestVersion},
 * {@link #ecsLoadObjectReturnsTheIsLatestVersion} and {@link #awsReplayWritesDataOnceThenDeleteMarker} fail (the last
 * one with the production symptom: the data version is uploaded as history and the "current" put re-reads its closed
 * stream).
 */
public class VersionChainCurrentVersionTest {
    private static final Date T = new Date(1_700_000_000_000L); // whole second: HEAD and listing agree
    private static final String KEY = "CommonInfo/RestorePoints/e98ffd57056a4694821fbf58128eac12.00000025";
    private static final String DATA_VID = "al-LNnf8mRKu3IHff3ODcEw6Xbtfsbn";   // IsLatest=false, sorts AFTER the marker by string
    private static final String DM_VID = "OAjKVXwBCo699Tuh551cLDys4.PorHY";     // IsLatest=true,  sorts BEFORE the data version
    private static final byte[] DATA = "restore point payload".getBytes(StandardCharsets.UTF_8);

    private static S3ObjectVersion version(SyncObject owner, String vid, boolean latest, boolean dm, Date mtime) {
        ObjectMetadata md = new ObjectMetadata().withModificationTime(mtime).withContentLength(dm ? 0 : DATA.length);
        return new S3ObjectVersion(owner.getSource(), KEY, md).withVersionId(vid).withLatest(latest).withDeleteMarker(dm);
    }

    @Test
    public void comparatorSortsCurrentVersionLast() {
        AwsS3Storage owner = awsStorage(new InMemoryAmazonS3("x"), true, RoleType.Source, quietOptions());
        SyncObject anchor = new SyncObject(owner, KEY, new ObjectMetadata());
        // tie on mtime, marker id sorts first
        List<S3ObjectVersion> tied = new ArrayList<>(Arrays.asList(
                version(anchor, DATA_VID, false, false, T),
                version(anchor, DM_VID, true, true, T)));
        tied.sort(new S3VersionComparator());
        Assertions.assertTrue(tied.get(tied.size() - 1).isLatest(), "IsLatest version must sort last on an mtime tie");

        // current version with an OLDER mtime than a non-current one (storage-preserved or skewed timestamps)
        List<S3ObjectVersion> skewed = new ArrayList<>(Arrays.asList(
                version(anchor, "cur", true, false, new Date(T.getTime() - 60_000)),
                version(anchor, "old", false, false, T)));
        skewed.sort(new S3VersionComparator());
        Assertions.assertEquals("cur", skewed.get(1).getVersionId(), "IsLatest version must sort last even with an older mtime");
    }

    @Test
    public void awsLoadObjectReturnsTheIsLatestVersion() {
        InMemoryAmazonS3 src = new InMemoryAmazonS3("src");
        src.addVersion(KEY, DATA_VID, InMemoryAmazonS3.md5Hex(DATA), false, false, T, DATA);
        src.addVersion(KEY, DM_VID, null, true, true, T, null);
        AwsS3Storage source = awsStorage(src, true, RoleType.Source, quietOptions());

        SyncObject current = source.loadObject(KEY);

        Assertions.assertTrue(current instanceof S3ObjectVersion);
        Assertions.assertEquals(DM_VID, ((S3ObjectVersion) current).getVersionId(), "the IsLatest version (the delete marker) must be the current object");
        Assertions.assertTrue(((S3ObjectVersion) current).isDeleteMarker());
        @SuppressWarnings("unchecked") List<S3ObjectVersion> chain = (List<S3ObjectVersion>) current.getProperty(AbstractS3Storage.PROP_OBJECT_VERSIONS);
        Assertions.assertEquals(Arrays.asList(DATA_VID, DM_VID), Arrays.asList(chain.get(0).getVersionId(), chain.get(1).getVersionId()));
    }

    @Test
    public void ecsLoadObjectReturnsTheIsLatestVersion() {
        InMemoryEcsS3Client src = new InMemoryEcsS3Client("src");
        src.addVersion(KEY, DATA_VID, InMemoryAmazonS3.md5Hex(DATA), false, false, T, DATA.length);
        src.addVersion(KEY, DM_VID, null, true, true, T, 0);
        EcsS3Storage source = ecsStorage(src, true, RoleType.Source, quietOptions());

        SyncObject current = source.loadObject(KEY);

        Assertions.assertTrue(current instanceof S3ObjectVersion);
        Assertions.assertEquals(DM_VID, ((S3ObjectVersion) current).getVersionId());
        Assertions.assertTrue(((S3ObjectVersion) current).isDeleteMarker());
    }

    @Test
    public void awsListingWithoutIsLatestFailsClearly() {
        InMemoryAmazonS3 src = new InMemoryAmazonS3("src");
        src.addVersion(KEY, DATA_VID, InMemoryAmazonS3.md5Hex(DATA), false, false, T, DATA);
        src.addVersion(KEY, DM_VID, null, false, true, T, null); // storage fault: nothing flagged IsLatest
        AwsS3Storage source = awsStorage(src, true, RoleType.Source, quietOptions());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> source.loadObject(KEY));
        Assertions.assertTrue(e.getMessage().contains("flagged as latest"), e.getMessage());
    }

    @Test
    public void awsReplayWritesDataOnceThenDeleteMarker() {
        InMemoryAmazonS3 src = new InMemoryAmazonS3("src");
        src.addVersion(KEY, DATA_VID, InMemoryAmazonS3.md5Hex(DATA), false, false, T, DATA);
        src.addVersion(KEY, DM_VID, null, true, true, T, null);
        InMemoryAmazonS3 tgt = new InMemoryAmazonS3("tgt");
        SyncOptions options = quietOptions();
        AwsS3Storage source = awsStorage(src, true, RoleType.Source, options);
        AwsS3Storage target = awsStorage(tgt, true, RoleType.Target, options);

        // first run: target is empty -> whole chain is replayed
        SyncObject current = source.loadObject(KEY);
        target.updateObject(KEY, current);

        Assertions.assertEquals(1, tgt.countRequests("PUT " + KEY), "the data version must be uploaded exactly once: " + tgt.log);
        Assertions.assertEquals(1, tgt.countRequests("DELETE " + KEY), "the current delete marker must be replicated: " + tgt.log);
        List<String> targetChain = tgt.versionIds(KEY); // newest first
        Assertions.assertEquals(2, targetChain.size());
        Assertions.assertTrue(targetChain.get(0).startsWith("dm"), "newest target version must be the delete marker: " + targetChain);
        Assertions.assertArrayEquals(DATA, tgt.data.get(KEY + "#" + targetChain.get(1)), "replayed data version content");

        // verification: the aggregate version checksum of the source chain equals that of the target chain
        SyncObject targetCurrent = target.loadObject(KEY);
        Assertions.assertEquals(current.getMd5Hex(true), targetCurrent.getMd5Hex(true), "aggregate verification MD5 must match after a correct replay");

        // second run: nothing changed -> no writes
        tgt.log.clear();
        SyncObject again = source.loadObject(KEY);
        target.updateObject(KEY, again);
        Assertions.assertEquals(0, tgt.countRequests("PUT "), "unchanged chain must not be re-uploaded: " + tgt.log);
        Assertions.assertEquals(0, tgt.countRequests("DELETE "), "unchanged chain must not be re-deleted: " + tgt.log);
        Assertions.assertEquals(0, tgt.countRequests("DeleteObjects"), "unchanged chain must not be replaced: " + tgt.log);
    }
}
