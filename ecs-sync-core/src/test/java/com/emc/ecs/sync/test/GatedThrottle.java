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

import engineering.clientside.throttle.Throttle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link Throttle} used as the job's shared throughput throttle to control the hand-off between a failed attempt and
 * its retry deterministically. The first {@code freeAcquires} calls return immediately; the next call blocks inside
 * {@code acquireDelayDuration} until {@link #release()} is called, which is exactly where EcsSync.submitForSync() waits
 * before re-queuing a retry. {@link #awaitGated(long, TimeUnit)} lets the test wait until that point has been reached.
 */
public class GatedThrottle implements Throttle {
    private final int freeAcquires;
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch gated = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    public GatedThrottle(int freeAcquires) {
        this.freeAcquires = freeAcquires;
    }

    /** waits until a caller is blocked in the gate */
    public boolean awaitGated(long timeout, TimeUnit unit) throws InterruptedException {
        return gated.await(timeout, unit);
    }

    public void release() {
        release.countDown();
    }

    public int getCalls() {
        return calls.get();
    }

    @Override
    public double getRate() {
        return 1;
    }

    @Override
    public void setRate(double permitsPerSecond) {
    }

    @Override
    public double acquire(int permits) throws InterruptedException {
        return acquireDelayDuration(permits) / 1e9;
    }

    @Override
    public long acquireDelayDuration(int permits) {
        if (calls.incrementAndGet() <= freeAcquires) return 0;
        gated.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    @Override
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public boolean tryAcquire(int permits) {
        return true;
    }

    @Override
    public long tryAcquireDelayDuration(int permits, long timeout, TimeUnit unit) {
        return 0;
    }
}
