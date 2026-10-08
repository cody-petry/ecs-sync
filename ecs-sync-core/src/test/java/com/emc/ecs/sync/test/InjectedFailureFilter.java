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
package com.emc.ecs.sync.test;

import com.emc.ecs.sync.filter.AbstractFilter;
import com.emc.ecs.sync.model.ObjectContext;
import com.emc.ecs.sync.model.SyncObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test filter that fails the first {@code failuresPerObject} attempts of every object with a RuntimeException (a
 * transient error that a retry recovers from) and passes later attempts through. {@code Integer.MAX_VALUE} makes every
 * attempt fail (retry exhaustion). Counters are shared across all sync threads.
 */
public class InjectedFailureFilter extends AbstractFilter<InjectedFailureFilter.Config> {
    public static class Config {
        public int failuresPerObject = 1;
        public final AtomicInteger attempts = new AtomicInteger();
        public final AtomicInteger failuresInjected = new AtomicInteger();
        public final AtomicInteger passes = new AtomicInteger();
    }

    public InjectedFailureFilter(int failuresPerObject) {
        Config c = new Config();
        c.failuresPerObject = failuresPerObject;
        setConfig(c);
    }

    @Override
    public void filter(ObjectContext objectContext) {
        config.attempts.incrementAndGet();
        // ObjectContext.getFailures() is the number of previous failed attempts of this object
        if (objectContext.getFailures() < config.failuresPerObject) {
            config.failuresInjected.incrementAndGet();
            throw new RuntimeException("injected transient failure #" + (objectContext.getFailures() + 1)
                    + " for " + objectContext.getSourceSummary().getIdentifier());
        }
        config.passes.incrementAndGet();
        getNext().filter(objectContext);
    }

    @Override
    public SyncObject reverseFilter(ObjectContext objectContext) {
        return getNext().reverseFilter(objectContext);
    }
}
