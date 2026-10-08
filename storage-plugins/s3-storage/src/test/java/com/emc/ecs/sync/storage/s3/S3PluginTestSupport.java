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
import com.emc.ecs.sync.config.storage.AwsS3Config;
import com.emc.ecs.sync.config.storage.EcsS3Config;
import com.emc.ecs.sync.model.ObjectSummary;
import com.emc.ecs.sync.storage.AbstractStorage;
import com.emc.ecs.sync.util.EnhancedThreadPoolExecutor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.regex.Pattern;

/**
 * Wires the S3 storage plugins to in-memory clients without calling {@code configure()}, which needs a live endpoint
 * (it builds the real client and probes the bucket). The private client fields are set reflectively; this is test
 * scaffolding only and does not change production code.
 */
final class S3PluginTestSupport {
    private S3PluginTestSupport() {
    }

    static void set(Object target, Class<?> declaring, String field, Object value) {
        try {
            Field f = declaring.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static AwsS3Storage awsStorage(InMemoryAmazonS3 client, boolean includeVersions, RoleType role, SyncOptions options, String... excludedKeys) {
        AwsS3Config cfg = new AwsS3Config();
        cfg.setBucketName(client.bucket);
        cfg.setAccessKey("a");
        cfg.setSecretKey("s");
        cfg.setKeyPrefix("");
        cfg.setIncludeVersions(includeVersions);
        if (excludedKeys.length > 0) cfg.setExcludedKeys(excludedKeys);
        AwsS3Storage st = new AwsS3Storage();
        st.setConfig(cfg);
        st.setOptions(options);
        set(st, AwsS3Storage.class, "s3", client);
        set(st, AwsS3Storage.class, "mpuThreadPool", new EnhancedThreadPoolExecutor(2, new LinkedBlockingDeque<>(100), "test-mpu-pool"));
        set(st, AbstractStorage.class, "role", role);
        if (excludedKeys.length > 0) {
            // configure() compiles these for the source role; replicate that here
            List<Pattern> patterns = new ArrayList<>();
            for (String p : excludedKeys) patterns.add(Pattern.compile(p));
            set(st, AwsS3Storage.class, "excludedKeyPatterns", patterns);
        }
        return st;
    }

    static EcsS3Storage ecsStorage(InMemoryEcsS3Client client, boolean includeVersions, RoleType role, SyncOptions options) {
        EcsS3Config cfg = new EcsS3Config();
        cfg.setBucketName(client.bucket);
        cfg.setAccessKey("a");
        cfg.setSecretKey("s");
        cfg.setHost("ecs.example.invalid");
        cfg.setKeyPrefix("");
        cfg.setIncludeVersions(includeVersions);
        EcsS3Storage st = new EcsS3Storage();
        st.setConfig(cfg);
        st.setOptions(options);
        set(st, EcsS3Storage.class, "s3", client.proxy());
        set(st, EcsS3Storage.class, "mpuThreadPool", new EnhancedThreadPoolExecutor(2, new LinkedBlockingDeque<>(100), "test-mpu-pool"));
        set(st, AbstractStorage.class, "role", role);
        return st;
    }

    static List<String> identifiers(Iterable<ObjectSummary> summaries) {
        List<String> ids = new ArrayList<>();
        for (ObjectSummary s : summaries) ids.add(s.getIdentifier());
        return ids;
    }

    static SyncOptions quietOptions() {
        return new SyncOptions().withMonitorPerformance(false).withEstimationEnabled(false);
    }
}
