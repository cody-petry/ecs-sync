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

import com.amazonaws.services.s3.model.MultiObjectDeleteException;
import com.emc.ecs.sync.config.RoleType;
import com.emc.ecs.sync.config.SyncOptions;
import com.emc.ecs.sync.model.ObjectMetadata;
import com.emc.object.s3.S3Exception;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static com.emc.ecs.sync.storage.s3.S3PluginTestSupport.*;

/**
 * Regression tests for the multi-object delete issued when a key's version history has to be replaced in the target
 * (review finding S3-2). S3 and RGW reject DeleteObjects requests with more than 1000 keys, so the request must be
 * batched. The ECS plugin always batched; the AWS plugin did not.
 * <p>
 * The replace is triggered with forceSync=true and a source chain whose only (current) version is a delete marker, so
 * that after the deletes the plugin only has to issue a single deleteObject (no data upload is needed in the stub).
 * <p>
 * Pre-fix (AWS): the 1001- and 2500-version cases fail ("one request with more than 1000 keys"). The error case checks
 * that a failing batch stops the replace (no further batches, no delete marker written) so that the object fails loudly.
 */
public class DeleteBatchingTest {
    private static final Date T = new Date(1_700_000_000_000L);
    private static final String KEY = "repo/RepositoryLock/lock";

    // ------------------------------------------------------------------ AWS

    private InMemoryAmazonS3 awsTargetWithVersions(int count) {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("tgt");
        for (int i = 1; i <= count; i++) {
            client.addVersion(KEY, String.format("v%05d", i), String.format("%032x", i), i == count, false, new Date(T.getTime() + i * 1000L), new byte[3]);
        }
        return client;
    }

    /** a source object whose version chain is a single, current delete marker */
    private static S3ObjectVersion deletedSourceObject(AwsS3Storage source) {
        ObjectMetadata md = new ObjectMetadata().withModificationTime(new Date(T.getTime() + 999_000L)).withContentLength(0);
        S3ObjectVersion dm = new S3ObjectVersion(source, KEY, md).withVersionId("srcdm").withLatest(true).withDeleteMarker(true);
        List<S3ObjectVersion> chain = new ArrayList<>(Collections.singletonList(dm));
        dm.setProperty(AbstractS3Storage.PROP_OBJECT_VERSIONS, chain);
        return dm;
    }

    private static void awsReplace(int targetVersionCount, InMemoryAmazonS3 target) {
        SyncOptions options = quietOptions().withForceSync(true);
        AwsS3Storage tgt = awsStorage(target, true, RoleType.Target, options);
        AwsS3Storage src = awsStorage(new InMemoryAmazonS3("src"), true, RoleType.Source, options);
        tgt.updateObject(KEY, deletedSourceObject(src));
    }

    @Test
    public void awsExactlyOneThousandVersionsIsOneRequest() {
        InMemoryAmazonS3 target = awsTargetWithVersions(1000);
        awsReplace(1000, target);
        Assertions.assertEquals(Arrays.asList(1000), target.deleteObjectsBatchSizes);
        Assertions.assertEquals(1, target.countRequests("DELETE " + KEY), "current delete marker must be replicated");
        Assertions.assertEquals(1, target.versionIds(KEY).size(), "only the new delete marker should remain");
    }

    @Test
    public void awsOneThousandAndOneVersionsIsBatched() {
        InMemoryAmazonS3 target = awsTargetWithVersions(1001);
        awsReplace(1001, target);
        for (int size : target.deleteObjectsBatchSizes) Assertions.assertTrue(size <= 1000, "one request with more than 1000 keys: " + target.deleteObjectsBatchSizes);
        Assertions.assertEquals(Arrays.asList(1000, 1), target.deleteObjectsBatchSizes);
        Assertions.assertEquals(1, target.versionIds(KEY).size());
    }

    @Test
    public void awsManyVersionsAreBatchedInOrder() {
        InMemoryAmazonS3 target = awsTargetWithVersions(2500);
        awsReplace(2500, target);
        Assertions.assertEquals(Arrays.asList(1000, 1000, 500), target.deleteObjectsBatchSizes);
        Assertions.assertEquals(1, target.countRequests("DELETE " + KEY));
        Assertions.assertEquals(1, target.versionIds(KEY).size());
    }

    @Test
    public void awsFailingBatchStopsTheReplaceLoudly() {
        InMemoryAmazonS3 target = awsTargetWithVersions(2500);
        target.failDeleteObjectsOnRequest = 2;
        Assertions.assertThrows(MultiObjectDeleteException.class, () -> awsReplace(2500, target));
        Assertions.assertEquals(Arrays.asList(1000, 1000), target.deleteObjectsBatchSizes, "no batch may be sent after a failure");
        Assertions.assertEquals(0, target.countRequests("DELETE " + KEY), "the delete marker must not be written after a failed delete");
        Assertions.assertEquals(1500, target.versionIds(KEY).size(), "the first batch was deleted, the rest must remain for the next attempt");
    }

    // ------------------------------------------------------------------ ECS (already batched; locks the behaviour in)

    private InMemoryEcsS3Client ecsTargetWithVersions(int count) {
        InMemoryEcsS3Client client = new InMemoryEcsS3Client("tgt");
        for (int i = 1; i <= count; i++) {
            client.addVersion(KEY, String.format("v%05d", i), String.format("%032x", i), i == count, false, new Date(T.getTime() + i * 1000L), 3);
        }
        return client;
    }

    private static void ecsReplace(InMemoryEcsS3Client target) {
        SyncOptions options = quietOptions().withForceSync(true);
        EcsS3Storage tgt = ecsStorage(target, true, RoleType.Target, options);
        EcsS3Storage src = ecsStorage(new InMemoryEcsS3Client("src"), true, RoleType.Source, options);
        ObjectMetadata md = new ObjectMetadata().withModificationTime(new Date(T.getTime() + 999_000L)).withContentLength(0);
        S3ObjectVersion dm = new S3ObjectVersion(src, KEY, md).withVersionId("srcdm").withLatest(true).withDeleteMarker(true);
        dm.setProperty(AbstractS3Storage.PROP_OBJECT_VERSIONS, new ArrayList<>(Collections.singletonList(dm)));
        tgt.updateObject(KEY, dm);
    }

    @Test
    public void ecsOneThousandAndOneVersionsIsBatched() {
        InMemoryEcsS3Client target = ecsTargetWithVersions(1001);
        ecsReplace(target);
        Assertions.assertEquals(Arrays.asList(1000, 1), target.deleteObjectsBatchSizes);
        Assertions.assertEquals(1, target.versionIds(KEY).size());
    }

    @Test
    public void ecsManyVersionsAreBatchedInOrder() {
        InMemoryEcsS3Client target = ecsTargetWithVersions(2500);
        ecsReplace(target);
        Assertions.assertEquals(Arrays.asList(1000, 1000, 500), target.deleteObjectsBatchSizes);
    }

    @Test
    public void ecsFailingBatchStopsTheReplaceLoudly() {
        InMemoryEcsS3Client target = ecsTargetWithVersions(2500);
        target.failDeleteObjectsOnRequest = 2;
        Assertions.assertThrows(S3Exception.class, () -> ecsReplace(target));
        Assertions.assertEquals(Arrays.asList(1000, 1000), target.deleteObjectsBatchSizes);
        Assertions.assertEquals(1500, target.versionIds(KEY).size());
    }
}
