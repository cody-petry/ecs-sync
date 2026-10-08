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
import com.emc.ecs.sync.model.SyncObject;
import com.emc.object.s3.bean.AbstractVersion;
import com.emc.object.s3.bean.DeleteMarker;
import com.emc.object.s3.bean.Version;
import com.amazonaws.services.s3.model.S3VersionSummary;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static com.emc.ecs.sync.storage.s3.S3PluginTestSupport.*;

/**
 * Version-chain ordering (review finding S3-1 / proposal 0005). A versioned key is replayed from an in-memory source
 * bucket to an in-memory target bucket and the test checks three things that production relies on:
 * <ol>
 *     <li>the target's version history ends up in the same storage order as the source's (newest-first listing order);</li>
 *     <li>the aggregate verification MD5 (the "versions" checksum used with {@code --verify}) matches between source and
 *     target;</li>
 *     <li>an unchanged second run performs no writes ("Source and target versions are the same").</li>
 * </ol>
 * The source histories have non-current entries that share one LastModified second (S3 listings have one-second
 * granularity on most dialects and a replay writes the historical versions back-to-back), so re-sorting the chain by
 * LastModified falls back to the version-id string, which is opaque and unrelated to write order on every dialect. The
 * fixtures assign scripted ids whose string order disagrees with write order on both sides, exactly as a real pair of
 * buckets can. Without proposal 0005 the same-second scenarios fail on all three checks (history replayed in the wrong
 * order, checksum mismatch, delete-all + replay on every run); with it they pass because both plugins keep the
 * listing's own order, which the SDK parsers preserve (see harness/proposals/SdkOrderCheck.java).
 * <p>
 * Every scenario runs against both S3 plugins (AWS SDK client and Dell object-client), with listing page sizes small
 * enough that histories cross page boundaries where the scenario says so.
 */
public class VersionChainOrderTest {
    private static final String KEY = "repo/CommonInfo/RestorePoints/2b1c8f0a.00000031";
    private static final Date T0 = new Date(1_700_000_000_000L); // whole seconds, like HEAD responses
    private static final Date T1 = new Date(T0.getTime() + 1000);
    private static final Date T2 = new Date(T0.getTime() + 2000);
    private static final Date TARGET_CLOCK = new Date(1_800_000_000_000L); // all target writes land in one second

    /** one entry of a version history in WRITE order (oldest first) */
    private static final class Entry {
        final String versionId;
        final byte[] content; // null = delete marker
        final Date mtime;

        Entry(String versionId, String content, Date mtime) {
            this.versionId = versionId;
            this.content = content == null ? null : content.getBytes(StandardCharsets.UTF_8);
            this.mtime = mtime;
        }

        boolean isDeleteMarker() {
            return content == null;
        }

        /** "DM" for a delete marker, otherwise the content MD5 (the ETag of a single-part object) */
        String signature() {
            return isDeleteMarker() ? "DM" : InMemoryAmazonS3.md5Hex(content);
        }
    }

    private static Entry data(String versionId, String content, Date mtime) {
        return new Entry(versionId, content, mtime);
    }

    private static Entry marker(String versionId, Date mtime) {
        return new Entry(versionId, null, mtime);
    }

    /** the two S3 plugins behind one fixture API */
    private interface Dialect {
        String name();

        void addSourceHistory(List<Entry> writeOrder);

        void scriptTargetIds(String... ids);

        void setPageSize(int pageSize);

        AbstractS3Storage<?> source();

        AbstractS3Storage<?> target();

        /** target versions newest first, as "DM" or content MD5 */
        List<String> targetSignatures();

        int targetRequests(String prefix);

        void clearTargetLog();

        List<String> targetLog();
    }

    private static final class AwsDialect implements Dialect {
        final InMemoryAmazonS3 src = new InMemoryAmazonS3("src");
        final InMemoryAmazonS3 tgt = new InMemoryAmazonS3("tgt");
        final SyncOptions options = quietOptions();
        final AwsS3Storage source = awsStorage(src, true, RoleType.Source, options);
        final AwsS3Storage target = awsStorage(tgt, true, RoleType.Target, options);

        AwsDialect() {
            tgt.clock = TARGET_CLOCK;
        }

        public String name() {
            return "aws";
        }

        public void addSourceHistory(List<Entry> writeOrder) {
            for (int i = 0; i < writeOrder.size(); i++) {
                Entry e = writeOrder.get(i);
                boolean latest = i == writeOrder.size() - 1;
                if (e.isDeleteMarker()) src.addVersion(KEY, e.versionId, null, latest, true, e.mtime, null);
                else src.addVersion(KEY, e.versionId, InMemoryAmazonS3.md5Hex(e.content), latest, false, e.mtime, e.content);
            }
        }

        public void scriptTargetIds(String... ids) {
            tgt.scriptedVersionIds.addAll(Arrays.asList(ids));
        }

        public void setPageSize(int pageSize) {
            src.pageSize = pageSize;
            tgt.pageSize = pageSize;
        }

        public AbstractS3Storage<?> source() {
            return source;
        }

        public AbstractS3Storage<?> target() {
            return target;
        }

        public List<String> targetSignatures() {
            List<String> out = new ArrayList<>();
            List<S3VersionSummary> vs = tgt.versions.get(KEY);
            if (vs != null) for (S3VersionSummary s : vs) out.add(s.isDeleteMarker() ? "DM" : s.getETag());
            return out;
        }

        public int targetRequests(String prefix) {
            return tgt.countRequests(prefix);
        }

        public void clearTargetLog() {
            tgt.log.clear();
        }

        public List<String> targetLog() {
            return tgt.log;
        }
    }

    private static final class EcsDialect implements Dialect {
        final InMemoryEcsS3Client src = new InMemoryEcsS3Client("src");
        final InMemoryEcsS3Client tgt = new InMemoryEcsS3Client("tgt");
        final SyncOptions options = quietOptions();
        final EcsS3Storage source = ecsStorage(src, true, RoleType.Source, options);
        final EcsS3Storage target = ecsStorage(tgt, true, RoleType.Target, options);

        EcsDialect() {
            tgt.clock = TARGET_CLOCK;
        }

        public String name() {
            return "ecs";
        }

        public void addSourceHistory(List<Entry> writeOrder) {
            for (int i = 0; i < writeOrder.size(); i++) {
                Entry e = writeOrder.get(i);
                boolean latest = i == writeOrder.size() - 1;
                if (e.isDeleteMarker()) src.addVersion(KEY, e.versionId, null, latest, true, e.mtime, 0);
                else src.addVersion(KEY, e.versionId, latest, e.mtime, e.content);
            }
        }

        public void scriptTargetIds(String... ids) {
            tgt.scriptedVersionIds.addAll(Arrays.asList(ids));
        }

        public void setPageSize(int pageSize) {
            src.pageSize = pageSize;
            tgt.pageSize = pageSize;
        }

        public AbstractS3Storage<?> source() {
            return source;
        }

        public AbstractS3Storage<?> target() {
            return target;
        }

        public List<String> targetSignatures() {
            List<String> out = new ArrayList<>();
            List<AbstractVersion> vs = tgt.versions.get(KEY);
            if (vs != null) for (AbstractVersion v : vs) out.add(v instanceof DeleteMarker ? "DM" : ((Version) v).getETag());
            return out;
        }

        public int targetRequests(String prefix) {
            return tgt.countRequests(prefix);
        }

        public void clearTargetLog() {
            tgt.log.clear();
        }

        public List<String> targetLog() {
            return tgt.log;
        }
    }

    // ------------------------------------------------------------------ the scenario driver

    /** newest-first signatures of a history given in write order */
    private static List<String> expectedListing(List<Entry> writeOrder) {
        List<String> out = new ArrayList<>();
        for (int i = writeOrder.size() - 1; i >= 0; i--) out.add(writeOrder.get(i).signature());
        return out;
    }

    private static List<String> chainVersionIds(SyncObject current) {
        @SuppressWarnings("unchecked") List<S3ObjectVersion> chain = (List<S3ObjectVersion>) current.getProperty(AbstractS3Storage.PROP_OBJECT_VERSIONS);
        List<String> ids = new ArrayList<>();
        for (S3ObjectVersion v : chain) ids.add(v.getVersionId());
        return ids;
    }

    private static List<String> writeOrderIds(List<Entry> writeOrder) {
        List<String> ids = new ArrayList<>();
        for (Entry e : writeOrder) ids.add(e.versionId);
        return ids;
    }

    /**
     * Loads the key from the source, replays it into the empty target, then checks storage order, the aggregate
     * verification checksum and that a second run is a no-op. All observations are collected first and asserted
     * together, so a failure report shows every consequence of a wrong chain order (replay order, checksum, second run).
     */
    private static void replayAndVerify(Dialect d, List<Entry> writeOrder, int expectedDataVersions, int expectedDeleteMarkers) {
        String tag = "[" + d.name() + "] ";

        // 1. the chain the source plugin builds (expected: the storage's write order, current version last)
        SyncObject current = d.source().loadObject(KEY);
        Assertions.assertTrue(current instanceof S3ObjectVersion);
        boolean currentIsLatest = ((S3ObjectVersion) current).isLatest();
        List<String> sourceChain = chainVersionIds(current);

        // 2. replay into the empty target
        d.target().updateObject(KEY, current);
        int puts = d.targetRequests("PUT " + KEY), deletes = d.targetRequests("DELETE " + KEY);
        List<String> firstRunLog = new ArrayList<>(d.targetLog());
        List<String> targetListing = d.targetSignatures();

        // 3. verification: aggregate MD5 over both chains
        SyncObject targetCurrent = d.target().loadObject(KEY);
        String sourceMd5 = current.getMd5Hex(true), targetMd5 = targetCurrent.getMd5Hex(true);
        boolean currentKindMatches = ((S3ObjectVersion) current).isDeleteMarker() == ((S3ObjectVersion) targetCurrent).isDeleteMarker();

        // 4. unchanged second run
        d.clearTargetLog();
        SyncObject again = d.source().loadObject(KEY);
        d.target().updateObject(KEY, again);
        int puts2 = d.targetRequests("PUT " + KEY), deletes2 = d.targetRequests("DELETE " + KEY), bulkDeletes2 = d.targetRequests("DeleteObjects");
        List<String> secondRunLog = new ArrayList<>(d.targetLog());

        Assertions.assertAll(tag + "version chain replay",
                () -> Assertions.assertTrue(currentIsLatest, tag + "the current object must be the IsLatest version"),
                () -> Assertions.assertEquals(writeOrderIds(writeOrder), sourceChain,
                        tag + "source version chain must follow the storage's write order (listing order reversed)"),
                () -> Assertions.assertEquals(expectedDataVersions, puts, tag + "data versions written in the first run: " + firstRunLog),
                () -> Assertions.assertEquals(expectedDeleteMarkers, deletes, tag + "delete markers written in the first run: " + firstRunLog),
                () -> Assertions.assertEquals(expectedListing(writeOrder), targetListing,
                        tag + "target version history (newest first) must be in the same order as the source's"),
                () -> Assertions.assertEquals(sourceMd5, targetMd5,
                        tag + "aggregate version checksum (verification) of source and target must match after the replay"),
                () -> Assertions.assertTrue(currentKindMatches, tag + "current version kind (data / delete marker) must match"),
                () -> Assertions.assertEquals(0, puts2 + deletes2 + bulkDeletes2,
                        tag + "an unchanged second run must perform no writes (PUT=" + puts2 + " DELETE=" + deletes2
                                + " DeleteObjects=" + bulkDeletes2 + "): " + secondRunLog));
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * Two non-current data versions written within one second, then the current version. Source ids are chosen so
     * that their string order is the reverse of the write order; so are the target's.
     */
    private static List<Entry> sameSecondNonCurrentVersions() {
        return Arrays.asList(
                data("zz-first", "alpha", T0),
                data("aa-second", "bravo", T0),
                data("cur", "charlie", T2));
    }

    @Test
    public void awsSameSecondNonCurrentVersions() {
        AwsDialect d = new AwsDialect();
        d.addSourceHistory(sameSecondNonCurrentVersions());
        d.scriptTargetIds("t-9", "t-1", "t-5");
        replayAndVerify(d, sameSecondNonCurrentVersions(), 3, 0);
    }

    @Test
    public void ecsSameSecondNonCurrentVersions() {
        EcsDialect d = new EcsDialect();
        d.addSourceHistory(sameSecondNonCurrentVersions());
        d.scriptTargetIds("t-9", "t-1", "t-5");
        replayAndVerify(d, sameSecondNonCurrentVersions(), 3, 0);
    }

    /**
     * Mixed history in one second: data, delete marker, data, and a current delete marker (an object that was
     * overwritten, deleted, re-created and deleted again within the listing's time resolution).
     */
    private static List<Entry> mixedHistoryWithCurrentDeleteMarker() {
        return Arrays.asList(
                data("v3", "alpha", T0),
                marker("v1", T0),
                data("v2", "bravo", T0),
                marker("v0", T0));
    }

    @Test
    public void awsMixedHistoryWithCurrentDeleteMarker() {
        AwsDialect d = new AwsDialect();
        d.addSourceHistory(mixedHistoryWithCurrentDeleteMarker());
        d.scriptTargetIds("t-3", "t-1", "t-2", "t-0");
        replayAndVerify(d, mixedHistoryWithCurrentDeleteMarker(), 2, 2);
    }

    @Test
    public void ecsMixedHistoryWithCurrentDeleteMarker() {
        EcsDialect d = new EcsDialect();
        d.addSourceHistory(mixedHistoryWithCurrentDeleteMarker());
        d.scriptTargetIds("t-3", "t-1", "t-2", "t-0");
        replayAndVerify(d, mixedHistoryWithCurrentDeleteMarker(), 2, 2);
    }

    /**
     * Five versions listed two per page (three pages, the same-second group split across the first and second page
     * from the end): the chain must be assembled across page boundaries in listing order.
     */
    private static List<Entry> sameSecondGroupAcrossPages() {
        return Arrays.asList(
                data("c", "alpha", T0),
                data("a", "bravo", T0),
                data("b", "charlie", T0),
                data("d", "delta", T1),
                data("e", "echo", T2));
    }

    @Test
    public void awsSameSecondGroupAcrossPages() {
        AwsDialect d = new AwsDialect();
        d.setPageSize(2);
        d.addSourceHistory(sameSecondGroupAcrossPages());
        d.scriptTargetIds("t-c", "t-a", "t-b", "t-d", "t-e");
        replayAndVerify(d, sameSecondGroupAcrossPages(), 5, 0);
        Assertions.assertTrue(d.src.countRequests("listNextBatchOfVersions") >= 2, "the source history must have been read across several pages: " + d.src.log);
    }

    @Test
    public void ecsSameSecondGroupAcrossPages() {
        EcsDialect d = new EcsDialect();
        d.setPageSize(2);
        d.addSourceHistory(sameSecondGroupAcrossPages());
        d.scriptTargetIds("t-c", "t-a", "t-b", "t-d", "t-e");
        replayAndVerify(d, sameSecondGroupAcrossPages(), 5, 0);
        Assertions.assertTrue(d.src.countRequests("listMoreVersions") >= 2, "the source history must have been read across several pages: " + d.src.log);
    }

    /**
     * Distinct timestamps across page boundaries (passes with and without proposal 0005): pins pagination itself.
     */
    private static List<Entry> distinctTimestampsAcrossPages() {
        return Arrays.asList(
                data("p1", "alpha", new Date(T0.getTime() - 4000)),
                marker("p2", new Date(T0.getTime() - 3000)),
                data("p3", "bravo", new Date(T0.getTime() - 2000)),
                data("p4", "charlie", new Date(T0.getTime() - 1000)),
                data("p5", "delta", T0));
    }

    @Test
    public void awsDistinctTimestampsAcrossPages() {
        AwsDialect d = new AwsDialect();
        d.setPageSize(2);
        d.addSourceHistory(distinctTimestampsAcrossPages());
        d.scriptTargetIds("t-1", "t-2", "t-3", "t-4", "t-5");
        replayAndVerify(d, distinctTimestampsAcrossPages(), 4, 1);
    }

    @Test
    public void ecsDistinctTimestampsAcrossPages() {
        EcsDialect d = new EcsDialect();
        d.setPageSize(2);
        d.addSourceHistory(distinctTimestampsAcrossPages());
        d.scriptTargetIds("t-1", "t-2", "t-3", "t-4", "t-5");
        replayAndVerify(d, distinctTimestampsAcrossPages(), 4, 1);
    }

    /**
     * A listing that does not return the IsLatest entry first is inconsistent; the chain must still end with the
     * current version so that loadObject() picks it and the replay writes it last (passes with and without 0005).
     */
    @Test
    public void awsIsLatestNotFirstInListingStillEndsTheChain() {
        AwsDialect d = new AwsDialect();
        d.src.addVersion(KEY, "old-a", InMemoryAmazonS3.md5Hex("alpha".getBytes(StandardCharsets.UTF_8)), false, false, T0, "alpha".getBytes(StandardCharsets.UTF_8));
        d.src.addVersion(KEY, "cur", InMemoryAmazonS3.md5Hex("charlie".getBytes(StandardCharsets.UTF_8)), true, false, T2, "charlie".getBytes(StandardCharsets.UTF_8));
        d.src.addVersion(KEY, "old-b", InMemoryAmazonS3.md5Hex("bravo".getBytes(StandardCharsets.UTF_8)), false, false, T1, "bravo".getBytes(StandardCharsets.UTF_8));
        // listing is now [old-b, cur*, old-a] (newest first would be [cur*, old-b, old-a])
        SyncObject current = d.source().loadObject(KEY);
        Assertions.assertEquals("cur", ((S3ObjectVersion) current).getVersionId());
        List<String> chain = chainVersionIds(current);
        Assertions.assertEquals(3, chain.size());
        Assertions.assertEquals("cur", chain.get(2), "the IsLatest version must be last in the chain: " + chain);
    }
}
