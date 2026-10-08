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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static com.emc.ecs.sync.storage.s3.S3PluginTestSupport.*;

/**
 * Regression tests for the bucket enumerations stopping at an empty-but-truncated listing page (review finding S3-4).
 * An S3 service may return a page with zero entries and IsTruncated=true; the deleted-key enumeration (both plugins) and
 * the ECS live-object enumeration previously ended there and silently skipped everything after it.
 * <p>
 * Pre-fix (v3.5.5 and v3.5.5+islatest-v2): the "empty page" tests fail because the key after the empty page is missing
 * from the enumeration. Post-fix: all keys are enumerated, normal termination is unchanged, and a listing that does not
 * make progress is reported instead of looping forever.
 * <p>
 * FIX 0003 completion (AWS live-object enumeration): the first version of FIX 0003 left {@code AwsS3Storage.PrefixIterator}
 * fetching the next page under a single {@code if}, so an empty truncated page whose marker had advanced still ended
 * the live-object enumeration (the "awsLiveObjects*" tests below fail on that version). The completed fix also reports an
 * empty truncated page that carries no continuation marker at all (the AWS SDK leaves {@code NextMarker} null when the
 * service omits it on an empty page; re-requesting with a null marker would restart from the beginning and never end).
 */
public class ListingPaginationTest {
    private static final Date T = new Date(1_700_000_000_000L);

    // ------------------------------------------------------------------ AWS plugin: deleted-key enumeration

    @Test
    public void awsDeletedKeysAfterEmptyTruncatedPageAreEnumerated() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptPage(true, "a/deletedA", "v1", true, T, "a/deletedA");
        client.scriptPage(true, "m/marker", "v0", true, T);                 // empty but truncated
        client.scriptPage(false, null, null, true, T, "z/deletedB");
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions());

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertTrue(ids.contains("a/deletedA"), ids.toString());
        Assertions.assertTrue(ids.contains("z/deletedB"), "deleted key after the empty truncated page must be enumerated: " + ids);
    }

    @Test
    public void awsConsecutiveEmptyTruncatedPagesAreSkipped() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptPage(true, "a/deletedA", "v1", true, T, "a/deletedA");
        client.scriptPage(true, "m/marker1", "v0", true, T);                // empty, truncated
        client.scriptPage(true, "m/marker2", "v0", true, T);                // empty, truncated, markers moved
        client.scriptPage(true, "m/marker3", "v0", true, T);                // empty, truncated, markers moved
        client.scriptPage(false, null, null, true, T, "z/deletedB", "z/deletedC");
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions());

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertEquals(Arrays.asList("a/deletedA", "z/deletedB", "z/deletedC"), ids);
    }

    @Test
    public void awsNormalPaginationTerminates() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.pageSize = 2;
        // 3 live keys (listObjects) and 3 deleted keys (listVersions); versions of a deleted key: data + delete marker
        for (int i = 0; i < 3; i++) client.addVersion("live" + i, "v1", "00000000000000000000000000000001", true, false, T, new byte[1]);
        for (int i = 0; i < 3; i++) {
            client.addVersion("gone" + i, "v1", "00000000000000000000000000000002", false, false, T, new byte[1]);
            client.addVersion("gone" + i, "dm1", null, true, true, new Date(T.getTime() + 1000), null);
        }
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions());

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertEquals(Arrays.asList("live0", "live1", "live2", "gone0", "gone1", "gone2"), ids);
        // 9 version summaries at 2 per page = 5 version pages; 3 live keys at 2 per page = 2 object pages
        Assertions.assertEquals(5, client.countRequests("listVersions") + client.countRequests("listNextBatchOfVersions"));
        Assertions.assertEquals(2, client.countRequests("listObjects") + client.countRequests("listNextBatchOfObjects"));
    }

    @Test
    public void awsEmptyFinalPageEndsEnumeration() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptPage(true, "a/deletedA", "v1", true, T, "a/deletedA");
        client.scriptPage(false, null, null, true, T);                      // empty and not truncated: the end
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions());

        Assertions.assertEquals(Arrays.asList("a/deletedA"), identifiers(storage.allObjects()));
        Assertions.assertEquals(2, client.countRequests("listVersions") + client.countRequests("listNextBatchOfVersions"));
    }

    @Test
    public void awsListingWithoutProgressIsReportedNotLooped() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptPage(true, "m/marker", "v0", true, T, "a/deletedA");
        client.scriptPage(true, "m/marker", "v0", true, T);                 // empty, truncated, SAME markers as before
        client.scriptPage(true, "m/marker", "v0", true, T);                 // would repeat forever on a faulty server
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> identifiers(storage.allObjects()));
        Assertions.assertTrue(e.getMessage().contains("not making progress"), e.getMessage());
    }

    // ------------------------------------------------------------------ AWS plugin: live-object enumeration (FIX 0003 completion)

    @Test
    public void awsLiveObjectsAfterInitialEmptyTruncatedPageAreEnumerated() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptObjectPage(true, "m/marker0");                          // very first page: empty but truncated
        client.scriptObjectPage(true, "a/one", "a/one");
        client.scriptObjectPage(false, null, "z/two");
        AwsS3Storage storage = awsStorage(client, false, RoleType.Source, quietOptions());

        Assertions.assertEquals(Arrays.asList("a/one", "z/two"), identifiers(storage.allObjects()));
        Assertions.assertEquals(3, client.countRequests("listObjects") + client.countRequests("listNextBatchOfObjects"));
    }

    @Test
    public void awsLiveObjectsAfterMiddleEmptyTruncatedPageAreEnumerated() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptObjectPage(true, "a/one", "a/one");
        client.scriptObjectPage(true, "m/marker");                           // empty but truncated, marker advanced
        client.scriptObjectPage(false, null, "z/two");
        AwsS3Storage storage = awsStorage(client, false, RoleType.Source, quietOptions());

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertEquals(Arrays.asList("a/one", "z/two"), ids);
        Assertions.assertTrue(client.log.contains("listNextBatchOfObjects marker=m/marker"),
                "the page after the empty one must be requested from the advanced marker: " + client.log);
    }

    @Test
    public void awsLiveObjectsAfterConsecutiveEmptyTruncatedPagesAreEnumerated() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptObjectPage(true, "a/one", "a/one");
        client.scriptObjectPage(true, "m/marker1");                          // empty, truncated
        client.scriptObjectPage(true, "m/marker2");                          // empty, truncated, marker moved
        client.scriptObjectPage(true, "m/marker3");                          // empty, truncated, marker moved
        client.scriptObjectPage(false, null, "z/two", "z/three");
        AwsS3Storage storage = awsStorage(client, false, RoleType.Source, quietOptions());

        Assertions.assertEquals(Arrays.asList("a/one", "z/two", "z/three"), identifiers(storage.allObjects()));
        Assertions.assertEquals(5, client.countRequests("listObjects") + client.countRequests("listNextBatchOfObjects"));
    }

    @Test
    public void awsLiveObjectsEmptyFinalPageEndsEnumeration() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptObjectPage(true, "a/one", "a/one");
        client.scriptObjectPage(false, null);                                // empty and not truncated: the end
        AwsS3Storage storage = awsStorage(client, false, RoleType.Source, quietOptions());

        Assertions.assertEquals(Arrays.asList("a/one"), identifiers(storage.allObjects()));
        Assertions.assertEquals(2, client.countRequests("listObjects") + client.countRequests("listNextBatchOfObjects"),
                "no request may follow a non-truncated page: " + client.log);
    }

    @Test
    public void awsLiveObjectsListingWithoutProgressIsReportedNotLooped() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptObjectPage(true, "m/marker", "a/one");
        client.scriptObjectPage(true, "m/marker");                           // empty, truncated, SAME marker as before
        client.scriptObjectPage(true, "m/marker");                           // would repeat forever on a faulty server
        client.scriptObjectPage(true, "m/marker");
        AwsS3Storage storage = awsStorage(client, false, RoleType.Source, quietOptions());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> identifiers(storage.allObjects()));
        Assertions.assertTrue(e.getMessage().contains("not making progress"), e.getMessage());
        Assertions.assertTrue(client.countRequests("listObjects") + client.countRequests("listNextBatchOfObjects") <= 3,
                "the enumeration must stop at the first page that did not move: " + client.log);
    }

    @Test
    public void awsLiveObjectsEmptyTruncatedPageWithoutMarkerIsReportedNotRestarted() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptObjectPage(true, "a/one", "a/one");
        client.scriptObjectPage(true, null);                                 // empty, truncated, no NextMarker (SDK leaves null)
        client.scriptObjectPage(true, "a/one", "a/one");                     // what a null-marker re-request returns: page 1 again
        client.scriptObjectPage(true, null);
        AwsS3Storage storage = awsStorage(client, false, RoleType.Source, quietOptions());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> identifiers(storage.allObjects()),
                "an empty truncated page without a continuation marker cannot be followed; it must be reported, not re-requested from the start");
        Assertions.assertTrue(e.getMessage().contains("not making progress"), e.getMessage());
        Assertions.assertEquals(2, client.countRequests("listObjects") + client.countRequests("listNextBatchOfObjects"), client.log.toString());
    }

    @Test
    public void awsDeletedKeysEmptyTruncatedPageWithoutMarkersIsReportedNotRestarted() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.scriptPage(true, "a/deletedA", "v1", true, T, "a/deletedA");
        client.scriptPage(true, null, null, true, T);                       // empty, truncated, no NextKeyMarker/NextVersionIdMarker
        client.scriptPage(true, "a/deletedA", "v1", true, T, "a/deletedA"); // a null-marker re-request would start over
        client.scriptPage(true, null, null, true, T);
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> identifiers(storage.allObjects()));
        Assertions.assertTrue(e.getMessage().contains("not making progress"), e.getMessage());
        Assertions.assertEquals(2, client.countRequests("listVersions") + client.countRequests("listNextBatchOfVersions"), client.log.toString());
    }

    // ------------------------------------------------------------------ ECS plugin: deleted-key and live-object enumerations

    @Test
    public void ecsDeletedKeysAfterEmptyTruncatedPageAreEnumerated() {
        InMemoryEcsS3Client client = new InMemoryEcsS3Client("src");
        client.scriptVersionPage(true, "a/deletedA", "v1", true, T, "a/deletedA");
        client.scriptVersionPage(true, "m/marker", "v0", true, T);
        client.scriptVersionPage(false, null, null, true, T, "z/deletedB");
        EcsS3Storage storage = ecsStorage(client, true, RoleType.Source, quietOptions());

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertTrue(ids.contains("z/deletedB"), "deleted key after the empty truncated page must be enumerated: " + ids);
    }

    @Test
    public void ecsLiveObjectsAfterEmptyTruncatedPageAreEnumerated() {
        InMemoryEcsS3Client client = new InMemoryEcsS3Client("src");
        client.scriptObjectPage(true, "a/one", "a/one");
        client.scriptObjectPage(true, "m/marker");                           // empty but truncated
        client.scriptObjectPage(false, null, "z/two");
        EcsS3Storage storage = ecsStorage(client, false, RoleType.Source, quietOptions());

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertEquals(Arrays.asList("a/one", "z/two"), ids);
    }

    @Test
    public void ecsNormalPaginationTerminates() {
        InMemoryEcsS3Client client = new InMemoryEcsS3Client("src");
        client.pageSize = 2;
        for (int i = 0; i < 3; i++) client.addVersion("live" + i, "v1", "00000000000000000000000000000001", true, false, T, 1);
        for (int i = 0; i < 3; i++) {
            client.addVersion("gone" + i, "v1", "00000000000000000000000000000002", false, false, T, 1);
            client.addVersion("gone" + i, "dm1", null, true, true, new Date(T.getTime() + 1000), 0);
        }
        EcsS3Storage storage = ecsStorage(client, true, RoleType.Source, quietOptions());

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertEquals(Arrays.asList("live0", "live1", "live2", "gone0", "gone1", "gone2"), ids);
    }

    @Test
    public void ecsListingWithoutProgressIsReportedNotLooped() {
        InMemoryEcsS3Client client = new InMemoryEcsS3Client("src");
        client.scriptObjectPage(true, "m/marker", "a/one");
        client.scriptObjectPage(true, "m/marker");
        client.scriptObjectPage(true, "m/marker");
        EcsS3Storage storage = ecsStorage(client, false, RoleType.Source, quietOptions());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> identifiers(storage.allObjects()));
        Assertions.assertTrue(e.getMessage().contains("not making progress"), e.getMessage());
    }

    @Test
    public void ecsLiveObjectsEmptyTruncatedPageWithoutMarkerIsReportedNotRestarted() {
        InMemoryEcsS3Client client = new InMemoryEcsS3Client("src");
        client.scriptObjectPage(true, "a/one", "a/one");
        client.scriptObjectPage(true, null);                                 // empty, truncated, no NextMarker
        client.scriptObjectPage(true, "a/one", "a/one");
        client.scriptObjectPage(true, null);
        EcsS3Storage storage = ecsStorage(client, false, RoleType.Source, quietOptions());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> identifiers(storage.allObjects()));
        Assertions.assertTrue(e.getMessage().contains("not making progress"), e.getMessage());
    }

    @Test
    public void ecsDeletedKeysEmptyTruncatedPageWithoutMarkersIsReportedNotRestarted() {
        InMemoryEcsS3Client client = new InMemoryEcsS3Client("src");
        client.scriptVersionPage(true, "a/deletedA", "v1", true, T, "a/deletedA");
        client.scriptVersionPage(true, null, null, true, T);                // empty, truncated, no markers
        client.scriptVersionPage(true, "a/deletedA", "v1", true, T, "a/deletedA");
        client.scriptVersionPage(true, null, null, true, T);
        EcsS3Storage storage = ecsStorage(client, true, RoleType.Source, quietOptions());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> identifiers(storage.allObjects()));
        Assertions.assertTrue(e.getMessage().contains("not making progress"), e.getMessage());
    }
}
