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
package com.emc.ecs.sync;

import com.emc.ecs.sync.config.SyncConfig;
import com.emc.ecs.sync.config.SyncOptions;
import com.emc.ecs.sync.config.storage.TestConfig;
import com.emc.ecs.sync.filter.SyncFilter;
import com.emc.ecs.sync.model.ObjectContext;
import com.emc.ecs.sync.model.ObjectStatus;
import com.emc.ecs.sync.service.InMemoryDbService;
import com.emc.ecs.sync.test.GatedThrottle;
import com.emc.ecs.sync.test.InjectedFailureFilter;
import com.emc.ecs.sync.util.EnhancedThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Balance checks for the in-flight object counter introduced with the CORE-1 fix (this test uses the new
 * {@code SyncStats.getObjectsInFlight()} API and therefore does not compile against the unfixed source).
 * Every scenario must end with the counter at zero; it must never be negative; and the per-executor task counter must
 * be back at zero after a task is rejected.
 */
public class InFlightAccountingTest {
    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private EcsSync sync;

    @AfterEach
    public void cleanup() {
        if (sync != null) sync.close();
        runner.shutdownNow();
    }

    private EcsSync newJob(int objectCount, InjectedFailureFilter filter, SyncOptions options) {
        TestConfig source = new TestConfig().withObjectCount(objectCount).withChanceOfChildren(0)
                .withMinSize(16).withMaxSize(64).withReadData(true).withDiscardData(false);
        TestConfig target = new TestConfig().withReadData(true).withDiscardData(false);
        filter.setOptions(options);
        List<SyncFilter<?>> filters = new ArrayList<>();
        filters.add(filter);
        sync = new EcsSync();
        sync.setSyncConfig(new SyncConfig().withSource(source).withTarget(target).withOptions(options));
        sync.setFilters(filters);
        sync.setDbService(new InMemoryDbService(false));
        return sync;
    }

    private static SyncOptions options() {
        return new SyncOptions().withThreadCount(3).withRetryAttempts(2).withEstimationEnabled(false)
                .withRememberFailed(true).withMonitorPerformance(false);
    }

    @Test
    public void counterIsZeroAfterSuccessfulJob() throws Exception {
        EcsSync sync = newJob(25, new InjectedFailureFilter(0), options());
        runner.submit(sync).get(120, TimeUnit.SECONDS);
        Assertions.assertEquals(25, sync.getStats().getObjectsComplete());
        Assertions.assertEquals(0, sync.getStats().getObjectsInFlight());
    }

    @Test
    public void counterIsZeroAfterRetriesAndExhaustion() throws Exception {
        // every object fails twice and succeeds on the third attempt: attempts = 1 + 2 retries = retryAttempts + 1
        EcsSync sync = newJob(20, new InjectedFailureFilter(2), options());
        runner.submit(sync).get(120, TimeUnit.SECONDS);
        Assertions.assertEquals(20, sync.getStats().getObjectsComplete());
        Assertions.assertEquals(0, sync.getStats().getObjectsFailed());
        Assertions.assertEquals(0, sync.getStats().getObjectsInFlight());

        // exhaustion: three failures exceed retryAttempts=2
        EcsSync exhausted = newJob(10, new InjectedFailureFilter(3), options());
        runner.submit(exhausted).get(120, TimeUnit.SECONDS);
        Assertions.assertEquals(10, exhausted.getStats().getObjectsFailed());
        Assertions.assertEquals(0, exhausted.getStats().getObjectsInFlight());
        exhausted.close();
    }

    @Test
    public void counterIsZeroWhenRetryCannotBeRecorded() throws Exception {
        EcsSync sync = newJob(5, new InjectedFailureFilter(1), options());
        sync.setDbService(new InMemoryDbService(false) {
            @Override
            public boolean setStatus(ObjectContext context, String error, boolean newRow) {
                if (context.getStatus() == ObjectStatus.RetryQueue) throw new RuntimeException("injected DB failure");
                return super.setStatus(context, error, newRow);
            }
        });
        runner.submit(sync).get(120, TimeUnit.SECONDS);
        Assertions.assertEquals(5, sync.getStats().getObjectsFailed());
        Assertions.assertEquals(0, sync.getStats().getObjectsInFlight());
    }

    @Test
    public void counterIsZeroAfterDelayedRetryCompletes() throws Exception {
        EcsSync sync = newJob(1, new InjectedFailureFilter(1), options());
        GatedThrottle throttle = new GatedThrottle(1);
        sync.setSharedThroughputThrottle(throttle);
        Future<?> run = runner.submit(sync);
        Assertions.assertTrue(throttle.awaitGated(60, TimeUnit.SECONDS));
        Assertions.assertEquals(1, sync.getStats().getObjectsInFlight(), "the retried object is still in flight while its re-submission is pending");
        throttle.release();
        run.get(60, TimeUnit.SECONDS);
        Assertions.assertEquals(1, sync.getStats().getObjectsComplete());
        Assertions.assertEquals(0, sync.getStats().getObjectsInFlight());
    }

    @Test
    public void counterNeverGoesNegativeOnTerminate() throws Exception {
        EcsSync sync = newJob(1, new InjectedFailureFilter(1), options());
        GatedThrottle throttle = new GatedThrottle(1);
        sync.setSharedThroughputThrottle(throttle);
        Future<?> run = runner.submit(sync);
        Assertions.assertTrue(throttle.awaitGated(60, TimeUnit.SECONDS));
        sync.terminate();
        throttle.release();
        try {
            run.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // terminate() may surface as a run error (pre-existing behaviour)
        }
        Assertions.assertTrue(sync.getStats().getObjectsInFlight() >= 0);
    }

    @Test
    public void unmatchedDecrementIsIgnored() {
        SyncStats stats = new SyncStats();
        stats.decObjectsInFlight();
        Assertions.assertEquals(0, stats.getObjectsInFlight(), "the counter must floor at zero");
        stats.incObjectsInFlight();
        Assertions.assertEquals(1, stats.getObjectsInFlight(), "a floored decrement must not offset a real increment");
        stats.decObjectsInFlight();
        Assertions.assertEquals(0, stats.getObjectsInFlight());
        stats.close();
    }

    @Test
    public void rejectedSubmissionDoesNotLeakUnfinishedTaskCount() throws Exception {
        EnhancedThreadPoolExecutor pool = new EnhancedThreadPoolExecutor(1, new LinkedBlockingDeque<>(1), "reject-test");
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        // occupy the single thread and fill the single queue slot
        pool.submit(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
            ran.incrementAndGet();
        });
        pool.submit(ran::incrementAndGet);
        Assertions.assertThrows(RejectedExecutionException.class, () -> pool.submit(ran::incrementAndGet));
        Assertions.assertEquals(2, pool.getUnfinishedTasks(), "a rejected task must not be counted");
        release.countDown();
        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        Assertions.assertEquals(2, ran.get());
        Assertions.assertEquals(0, pool.getUnfinishedTasks());
    }
}
