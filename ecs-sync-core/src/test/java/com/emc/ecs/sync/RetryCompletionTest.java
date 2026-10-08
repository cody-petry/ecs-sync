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
import com.emc.ecs.sync.service.DbService;
import com.emc.ecs.sync.service.InMemoryDbService;
import com.emc.ecs.sync.service.SyncRecord;
import com.emc.ecs.sync.storage.TestStorage;
import com.emc.ecs.sync.test.GatedThrottle;
import com.emc.ecs.sync.test.InjectedFailureFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Regression tests for retries at the end of a job (review finding CORE-1): a failed object is handed from the sync
 * pool to the retry-submitter pool; while it waits there (e.g. on the throughput throttle) every executor the completion
 * check consulted was idle, so the job declared "all tasks complete", shut down and the retried object was never
 * written, never counted as failed, and left at 'Retry Queue' in the tracking DB.
 * <p>
 * The hand-off is made deterministic with {@link GatedThrottle}: the retry's throttle acquire blocks until the test
 * releases it. Pre-fix, {@link #retryIsNotLostWhileItsSubmissionIsDelayed} fails with "job completed while a retry was
 * still pending" and the follow-up assertions (object copied, DB row Transferred). The other tests pin the surrounding
 * accounting paths (exhaustion, DB write failure during re-queue, terminate while a retry is pending).
 */
public class RetryCompletionTest {
    private static final long RETRY_HOLD_SECONDS = 5; // the completion loop polls every second; 5 polls must not end the job

    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private EcsSync sync;

    @AfterEach
    public void cleanup() {
        if (sync != null) sync.close();
        runner.shutdownNow();
    }

    private EcsSync newJob(int objectCount, InjectedFailureFilter filter, SyncOptions options, DbService dbService) {
        TestConfig source = new TestConfig().withObjectCount(objectCount).withChanceOfChildren(0)
                .withMinSize(16).withMaxSize(64).withReadData(true).withDiscardData(false);
        TestConfig target = new TestConfig().withReadData(true).withDiscardData(false);
        filter.setOptions(options);
        List<SyncFilter<?>> filters = new ArrayList<>();
        filters.add(filter);
        sync = new EcsSync();
        sync.setSyncConfig(new SyncConfig().withSource(source).withTarget(target).withOptions(options));
        sync.setFilters(filters);
        sync.setDbService(dbService);
        return sync;
    }

    private static SyncOptions options(int retryAttempts) {
        return new SyncOptions().withThreadCount(2).withRetryAttempts(retryAttempts)
                .withEstimationEnabled(false).withRememberFailed(true).withMonitorPerformance(false);
    }

    private static List<SyncRecord> records(DbService db) {
        List<SyncRecord> out = new ArrayList<>();
        for (SyncRecord r : db.<SyncRecord>getAllRecords()) out.add(r);
        return out;
    }

    private static int targetObjectCount(EcsSync sync) {
        return ((TestStorage) sync.getTarget()).getRootObjects().size();
    }

    @Test
    public void retryIsNotLostWhileItsSubmissionIsDelayed() throws Exception {
        InjectedFailureFilter filter = new InjectedFailureFilter(1); // fails once, succeeds on retry
        InMemoryDbService db = new InMemoryDbService(false);
        EcsSync sync = newJob(1, filter, options(2), db);
        // first acquire: the initial submission; second acquire: the retry -> gated
        GatedThrottle throttle = new GatedThrottle(1);
        sync.setSharedThroughputThrottle(throttle);

        Future<?> run = runner.submit(sync);
        Assertions.assertTrue(throttle.awaitGated(60, TimeUnit.SECONDS), "the retry never reached the throttle");

        // the retry is now in the hand-off between the sync pool and its re-submission; the job must keep waiting
        try {
            run.get(RETRY_HOLD_SECONDS, TimeUnit.SECONDS);
            Assertions.fail("job completed while a retry was still pending (lost retry): complete="
                    + sync.getStats().getObjectsComplete() + " failed=" + sync.getStats().getObjectsFailed());
        } catch (TimeoutException expected) {
            // still running - correct
        } finally {
            throttle.release();
        }
        run.get(60, TimeUnit.SECONDS);

        Assertions.assertEquals(1, sync.getStats().getObjectsComplete(), "retried object must be counted complete");
        Assertions.assertEquals(0, sync.getStats().getObjectsFailed());
        Assertions.assertEquals(2, filter.getConfig().attempts.get(), "one failed attempt plus one successful retry");
        Assertions.assertEquals(1, targetObjectCount(sync), "the retried object must exist in the target");
        List<SyncRecord> rows = records(db);
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals(ObjectStatus.Transferred, rows.get(0).getStatus(), "tracking DB must show the terminal state, not 'Retry Queue'");
        Assertions.assertEquals(1, rows.get(0).getRetryCount());
        Assertions.assertFalse(db.getSyncRetries().iterator().hasNext(), "no rows may be left at 'Retry Queue'");
    }

    @Test
    public void retryExhaustionIsRecordedAsErrorAndJobCompletes() throws Exception {
        InjectedFailureFilter filter = new InjectedFailureFilter(Integer.MAX_VALUE); // never succeeds
        InMemoryDbService db = new InMemoryDbService(false);
        EcsSync sync = newJob(3, filter, options(2), db);

        runner.submit(sync).get(120, TimeUnit.SECONDS);

        Assertions.assertEquals(0, sync.getStats().getObjectsComplete());
        Assertions.assertEquals(3, sync.getStats().getObjectsFailed());
        Assertions.assertEquals(3, sync.getStats().getFailedObjects().size());
        Assertions.assertEquals(9, filter.getConfig().attempts.get(), "3 objects x (1 attempt + 2 retries)");
        Assertions.assertEquals(0, targetObjectCount(sync));
        List<SyncRecord> rows = records(db);
        Assertions.assertEquals(3, rows.size());
        for (SyncRecord r : rows) {
            Assertions.assertEquals(ObjectStatus.Error, r.getStatus(), r.getSourceId());
            Assertions.assertEquals(2, r.getRetryCount(), r.getSourceId());
        }
        int errors = 0;
        for (SyncRecord ignored : db.<SyncRecord>getSyncErrors()) errors++;
        Assertions.assertEquals(3, errors);
    }

    @Test
    public void retryWhoseDbStatusWriteFailsIsRecordedAsError() throws Exception {
        InjectedFailureFilter filter = new InjectedFailureFilter(1);
        InMemoryDbService db = new InMemoryDbService(false) {
            @Override
            public boolean setStatus(ObjectContext context, String error, boolean newRow) {
                // the re-queue path writes 'Retry Queue' before submitting the retry; make that write fail
                if (context.getStatus() == ObjectStatus.RetryQueue) throw new RuntimeException("injected DB failure");
                return super.setStatus(context, error, newRow);
            }
        };
        EcsSync sync = newJob(2, filter, options(2), db);

        runner.submit(sync).get(120, TimeUnit.SECONDS);

        // the retry could not be queued, so the object fails immediately (the original error is kept)
        Assertions.assertEquals(2, sync.getStats().getObjectsFailed());
        Assertions.assertEquals(0, sync.getStats().getObjectsComplete());
        Assertions.assertEquals(2, filter.getConfig().attempts.get(), "no retry may run when it could not be recorded");
        for (SyncRecord r : records(db)) Assertions.assertEquals(ObjectStatus.Error, r.getStatus(), r.getSourceId());
        Assertions.assertEquals(0, targetObjectCount(sync));
    }

    @Test
    public void terminateWhileRetryIsPendingDoesNotHang() throws Exception {
        InjectedFailureFilter filter = new InjectedFailureFilter(1);
        InMemoryDbService db = new InMemoryDbService(false);
        EcsSync sync = newJob(1, filter, options(2), db);
        GatedThrottle throttle = new GatedThrottle(1);
        sync.setSharedThroughputThrottle(throttle);

        Future<?> run = runner.submit(sync);
        Assertions.assertTrue(throttle.awaitGated(60, TimeUnit.SECONDS));

        sync.terminate();
        throttle.release();
        try {
            run.get(60, TimeUnit.SECONDS); // must return: a terminated job must not wait for discarded retries
        } catch (ExecutionException e) {
            // terminate() can surface as a run error (pre-existing behaviour, CORE-18); not a hang
        }

        Assertions.assertTrue(sync.isTerminated());
        Assertions.assertFalse(sync.isRunning());
        Assertions.assertEquals(0, sync.getStats().getObjectsComplete(), "a retry discarded by terminate() must not be counted complete");
    }
}
