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
 * Regression tests for excludedKeys with includeVersions=true on the AWS plugin (review finding S3-5): the exclusion
 * patterns were applied to live objects only, so a currently-deleted key under an excluded prefix was still enumerated
 * (and its whole version history replicated). The ECS plugin has no excludedKeys option, so there is no ECS variant.
 * <p>
 * Pre-fix: {@link #excludedDeletedKeysAreNotEnumerated} and {@link #exclusionAppliesAcrossPages} fail because the deleted
 * key matching the pattern is enumerated.
 */
public class ExcludedKeysTest {
    private static final Date T = new Date(1_700_000_000_000L);

    private static InMemoryAmazonS3 bucket() {
        InMemoryAmazonS3 client = new InMemoryAmazonS3("src");
        client.addVersion("logs/live.log", "v1", "0000000000000000000000000000000a", true, false, T, new byte[3]);
        client.addVersion("logs/deleted.log", "v1", "0000000000000000000000000000000b", false, false, T, new byte[3]);
        client.addVersion("logs/deleted.log", "dm1", null, true, true, new Date(T.getTime() + 1000), null);
        client.addVersion("data/keep.bin", "v1", "0000000000000000000000000000000c", true, false, T, new byte[3]);
        client.addVersion("data/removed.bin", "v1", "0000000000000000000000000000000d", false, false, T, new byte[3]);
        client.addVersion("data/removed.bin", "dm1", null, true, true, new Date(T.getTime() + 1000), null);
        return client;
    }

    @Test
    public void excludedDeletedKeysAreNotEnumerated() {
        InMemoryAmazonS3 client = bucket();
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions(), "logs/.*");

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertFalse(ids.contains("logs/live.log"), ids.toString());
        Assertions.assertFalse(ids.contains("logs/deleted.log"), "currently-deleted key under an excluded prefix was enumerated: " + ids);
        Assertions.assertEquals(Arrays.asList("data/keep.bin", "data/removed.bin"), ids);
    }

    @Test
    public void includedDeletedKeysAreStillEnumerated() {
        InMemoryAmazonS3 client = bucket();
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions(), "nothing-matches/.*");

        List<String> ids = identifiers(storage.allObjects());

        // live keys first (PrefixIterator), then currently-deleted keys (DeletedObjectIterator)
        Assertions.assertEquals(Arrays.asList("data/keep.bin", "logs/live.log", "data/removed.bin", "logs/deleted.log"), ids);
    }

    @Test
    public void withoutExclusionsEverythingIsEnumerated() {
        InMemoryAmazonS3 client = bucket();
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions());

        Assertions.assertEquals(Arrays.asList("data/keep.bin", "logs/live.log", "data/removed.bin", "logs/deleted.log"), identifiers(storage.allObjects()));
    }

    @Test
    public void exclusionAppliesAcrossPages() {
        InMemoryAmazonS3 client = bucket();
        client.pageSize = 1; // every version summary on its own page, so excluded deleted keys appear on later pages
        AwsS3Storage storage = awsStorage(client, true, RoleType.Source, quietOptions(), "logs/.*");

        List<String> ids = identifiers(storage.allObjects());

        Assertions.assertEquals(Arrays.asList("data/keep.bin", "data/removed.bin"), ids);
        // 6 version summaries => 6 version pages were fetched even though only one key survived the filter
        Assertions.assertEquals(6, client.countRequests("listVersions") + client.countRequests("listNextBatchOfVersions"));
    }
}
