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

import com.amazonaws.services.s3.AbstractAmazonS3;
import com.amazonaws.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;

/**
 * Minimal in-memory versioned-bucket implementation of {@link com.amazonaws.services.s3.AmazonS3} for offline unit
 * tests of {@link AwsS3Storage}. Versions of a key are kept in listing order (newest first, the way S3 returns them).
 * <p>
 * Features used by the tests:
 * <ul>
 * <li>real pagination of {@code listVersions}/{@code listNextBatchOfVersions} (page size {@link #pageSize}, key/version-id
 * markers) and of {@code listObjects}/{@code listNextBatchOfObjects}</li>
 * <li>scripted version-listing pages ({@link #scriptedVersionPages}) to simulate empty-but-truncated pages or a listing
 * that does not make progress</li>
 * <li>{@code deleteObjects} records the size of every request ({@link #deleteObjectsBatchSizes}) and can be made to fail
 * on the n-th request ({@link #failDeleteObjectsOnRequest})</li>
 * <li>a request log ({@link #log})</li>
 * </ul>
 */
public class InMemoryAmazonS3 extends AbstractAmazonS3 {
    final String bucket;
    /** key -> versions in listing order (newest first) */
    final Map<String, List<S3VersionSummary>> versions = new TreeMap<>();
    final Map<String, byte[]> data = new HashMap<>();
    final List<Integer> deleteObjectsBatchSizes = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    /** 1-based index of the deleteObjects request that should fail (0 = never) */
    int failDeleteObjectsOnRequest = 0;
    /** maximum number of version summaries per listVersions page */
    int pageSize = 1000;
    /** if non-empty, listVersions/listNextBatchOfVersions return these pages in order instead of the real listing */
    final Deque<VersionListing> scriptedVersionPages = new ArrayDeque<>();
    /** if non-empty, listObjects/listNextBatchOfObjects return these pages in order instead of the real listing */
    final Deque<ObjectListing> scriptedObjectPages = new ArrayDeque<>();
    int versionCounter = 0;
    /**
     * if non-empty, putObject/deleteObject assign these version ids (in order) to the versions they create instead of
     * the counter-based ids; real S3 dialects assign opaque ids whose string order is unrelated to write order
     */
    final Deque<String> scriptedVersionIds = new ArrayDeque<>();
    /** LastModified assigned to versions created by putObject/deleteObject (null = wall clock) */
    Date clock = null;

    private String nextVersionId(String prefix) {
        String scripted = scriptedVersionIds.poll();
        return scripted != null ? scripted : prefix + (++versionCounter);
    }

    private Date now() {
        return clock != null ? clock : new Date();
    }

    public InMemoryAmazonS3(String bucket) {
        this.bucket = bucket;
    }

    // ------------------------------------------------------------------ fixture helpers

    public S3VersionSummary addVersion(String key, String versionId, String etag, boolean latest, boolean deleteMarker, Date mtime, byte[] content) {
        S3VersionSummary s = new S3VersionSummary();
        s.setBucketName(bucket);
        s.setKey(key);
        s.setVersionId(versionId);
        s.setETag(etag);
        s.setIsLatest(latest);
        s.setIsDeleteMarker(deleteMarker);
        s.setLastModified(mtime);
        s.setSize(content == null ? 0 : content.length);
        versions.computeIfAbsent(key, k -> new ArrayList<>()).add(0, s); // newest first
        if (content != null) data.put(key + "#" + versionId, content);
        return s;
    }

    /** adds a page to the script; {@code keys} become delete-marker (if {@code deleteMarkers}) or data summaries that are all IsLatest */
    public VersionListing scriptPage(boolean truncated, String nextKeyMarker, String nextVersionIdMarker, boolean deleteMarkers, Date mtime, String... keys) {
        VersionListing page = new VersionListing();
        page.setBucketName(bucket);
        page.setMaxKeys(pageSize);
        page.setTruncated(truncated);
        page.setNextKeyMarker(nextKeyMarker);
        page.setNextVersionIdMarker(nextVersionIdMarker);
        int i = 0;
        for (String key : keys) {
            S3VersionSummary s = new S3VersionSummary();
            s.setBucketName(bucket);
            s.setKey(key);
            s.setVersionId("v" + (++i));
            s.setIsLatest(true);
            s.setIsDeleteMarker(deleteMarkers);
            s.setLastModified(mtime);
            s.setETag(deleteMarkers ? null : "00000000000000000000000000000001");
            s.setSize(deleteMarkers ? 0 : 1);
            page.getVersionSummaries().add(s);
        }
        scriptedVersionPages.add(page);
        return page;
    }

    /**
     * adds a live-object (listObjects) page to the script; {@code nextMarker} is what the SDK would expose after parsing
     * the response (null = the service omitted NextMarker on an empty page, which is what the AWS SDK leaves behind
     * when the page carries no key it could fall back to)
     */
    public ObjectListing scriptObjectPage(boolean truncated, String nextMarker, String... keys) {
        ObjectListing page = new ObjectListing();
        page.setBucketName(bucket);
        page.setMaxKeys(pageSize);
        page.setTruncated(truncated);
        page.setNextMarker(nextMarker);
        for (String key : keys) {
            S3ObjectSummary s = new S3ObjectSummary();
            s.setBucketName(bucket);
            s.setKey(key);
            s.setSize(1);
            s.setETag("00000000000000000000000000000001");
            s.setLastModified(new Date(1_700_000_000_000L));
            page.getObjectSummaries().add(s);
        }
        scriptedObjectPages.add(page);
        return page;
    }

    public int countRequests(String prefix) {
        int n = 0;
        for (String l : log) if (l.startsWith(prefix)) n++;
        return n;
    }

    public List<String> versionIds(String key) {
        List<String> ids = new ArrayList<>();
        List<S3VersionSummary> vs = versions.get(key);
        if (vs != null) for (S3VersionSummary s : vs) ids.add(s.getVersionId());
        return ids;
    }

    // ------------------------------------------------------------------ listing

    private List<S3VersionSummary> allVersions(String prefix) {
        List<S3VersionSummary> out = new ArrayList<>();
        for (Map.Entry<String, List<S3VersionSummary>> e : versions.entrySet()) {
            if (prefix != null && !e.getKey().startsWith(prefix)) continue;
            out.addAll(e.getValue());
        }
        return out;
    }

    private VersionListing page(String prefix, String delimiter, String keyMarker, String versionIdMarker) {
        List<S3VersionSummary> all = allVersions(prefix);
        int start = 0;
        if (keyMarker != null) {
            for (int i = 0; i < all.size(); i++) {
                S3VersionSummary s = all.get(i);
                if (s.getKey().equals(keyMarker) && (versionIdMarker == null || s.getVersionId().equals(versionIdMarker))) {
                    start = i + 1;
                    break;
                }
            }
        }
        VersionListing l = new VersionListing();
        l.setBucketName(bucket);
        l.setPrefix(prefix);
        l.setDelimiter(delimiter);
        l.setMaxKeys(pageSize);
        l.setKeyMarker(keyMarker);
        l.setVersionIdMarker(versionIdMarker);
        int end = Math.min(start + pageSize, all.size());
        l.getVersionSummaries().addAll(all.subList(start, end));
        if (end < all.size()) {
            l.setTruncated(true);
            S3VersionSummary last = all.get(end - 1);
            l.setNextKeyMarker(last.getKey());
            l.setNextVersionIdMarker(last.getVersionId());
        } else {
            l.setTruncated(false);
        }
        return l;
    }

    @Override
    public VersionListing listVersions(ListVersionsRequest req) {
        log.add("listVersions prefix=" + req.getPrefix() + " delimiter=" + req.getDelimiter() + " keyMarker=" + req.getKeyMarker());
        if (!scriptedVersionPages.isEmpty()) return scriptedVersionPages.poll();
        return page(req.getPrefix(), req.getDelimiter(), req.getKeyMarker(), req.getVersionIdMarker());
    }

    @Override
    public VersionListing listNextBatchOfVersions(VersionListing previous) {
        log.add("listNextBatchOfVersions keyMarker=" + previous.getNextKeyMarker() + " versionIdMarker=" + previous.getNextVersionIdMarker());
        if (!scriptedVersionPages.isEmpty()) return scriptedVersionPages.poll();
        return page(previous.getPrefix(), previous.getDelimiter(), previous.getNextKeyMarker(), previous.getNextVersionIdMarker());
    }

    @Override
    public VersionListing listNextBatchOfVersions(ListNextBatchOfVersionsRequest req) {
        return listNextBatchOfVersions(req.getPreviousVersionListing());
    }

    private ObjectListing objectPage(String prefix, String marker) {
        List<S3ObjectSummary> all = new ArrayList<>();
        for (Map.Entry<String, List<S3VersionSummary>> e : versions.entrySet()) {
            if (prefix != null && !e.getKey().startsWith(prefix)) continue;
            S3VersionSummary latest = e.getValue().get(0);
            if (latest.isDeleteMarker()) continue;
            S3ObjectSummary s = new S3ObjectSummary();
            s.setBucketName(bucket);
            s.setKey(e.getKey());
            s.setSize(latest.getSize());
            s.setETag(latest.getETag());
            s.setLastModified(latest.getLastModified());
            all.add(s);
        }
        int start = 0;
        if (marker != null) for (int i = 0; i < all.size(); i++) if (all.get(i).getKey().equals(marker)) start = i + 1;
        ObjectListing l = new ObjectListing();
        l.setBucketName(bucket);
        l.setPrefix(prefix);
        l.setMaxKeys(pageSize);
        l.setMarker(marker);
        int end = Math.min(start + pageSize, all.size());
        l.getObjectSummaries().addAll(all.subList(start, end));
        if (end < all.size()) {
            l.setTruncated(true);
            l.setNextMarker(all.get(end - 1).getKey());
        }
        return l;
    }

    @Override
    public ObjectListing listObjects(ListObjectsRequest req) {
        log.add("listObjects prefix=" + req.getPrefix() + " marker=" + req.getMarker());
        if (!scriptedObjectPages.isEmpty()) return scriptedObjectPages.poll();
        return objectPage(req.getPrefix(), req.getMarker());
    }

    @Override
    public ObjectListing listNextBatchOfObjects(ObjectListing previous) {
        log.add("listNextBatchOfObjects marker=" + previous.getNextMarker());
        if (!scriptedObjectPages.isEmpty()) return scriptedObjectPages.poll();
        // like the real SDK: the next request starts at the previous page's NextMarker (null = from the beginning)
        return objectPage(previous.getPrefix(), previous.getNextMarker());
    }

    @Override
    public ObjectListing listNextBatchOfObjects(ListNextBatchOfObjectsRequest req) {
        return listNextBatchOfObjects(req.getPreviousObjectListing());
    }

    // ------------------------------------------------------------------ object operations

    @Override
    public ObjectMetadata getObjectMetadata(GetObjectMetadataRequest req) {
        log.add("HEAD " + req.getKey() + " v=" + req.getVersionId());
        List<S3VersionSummary> vs = versions.get(req.getKey());
        if (vs == null) throw notFound();
        S3VersionSummary match = null;
        for (S3VersionSummary s : vs) {
            if (req.getVersionId() == null ? s.isLatest() : s.getVersionId().equals(req.getVersionId())) {
                match = s;
                break;
            }
        }
        if (match == null || match.isDeleteMarker()) throw notFound();
        ObjectMetadata om = new ObjectMetadata();
        om.setContentLength(match.getSize());
        // real HEAD responses carry whole-second Last-Modified values
        om.setLastModified(new Date(match.getLastModified().getTime() / 1000 * 1000));
        om.setHeader("ETag", match.getETag());
        om.setContentType("application/octet-stream");
        return om;
    }

    @Override
    public S3Object getObject(GetObjectRequest req) {
        byte[] d = data.get(req.getKey() + "#" + req.getVersionId());
        if (d == null) throw notFound();
        log.add("GET " + req.getKey() + " v=" + req.getVersionId());
        S3Object o = new S3Object();
        o.setObjectContent(new ByteArrayInputStream(d));
        return o;
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest req) {
        try {
            InputStream in = req.getInputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                total += n;
            }
            in.close(); // AmazonS3Client.putObject() closes the request stream after the upload
            String vid = nextVersionId("tv");
            log.add("PUT " + req.getKey() + " bytes=" + total + " -> " + vid);
            List<S3VersionSummary> vs = versions.get(req.getKey());
            if (vs != null) for (S3VersionSummary s : vs) s.setIsLatest(false);
            addVersion(req.getKey(), vid, md5Hex(bos.toByteArray()), true, false, now(), bos.toByteArray());
            PutObjectResult r = new PutObjectResult();
            r.setETag(md5Hex(bos.toByteArray()));
            r.setVersionId(vid);
            return r;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteObject(String bucketName, String key) {
        String vid = nextVersionId("dm");
        log.add("DELETE " + key + " -> delete marker " + vid);
        List<S3VersionSummary> vs = versions.get(key);
        if (vs != null) for (S3VersionSummary s : vs) s.setIsLatest(false);
        addVersion(key, vid, null, true, true, now(), null);
    }

    @Override
    public DeleteObjectsResult deleteObjects(DeleteObjectsRequest req) {
        deleteObjectsBatchSizes.add(req.getKeys().size());
        log.add("DeleteObjects keys=" + req.getKeys().size());
        if (failDeleteObjectsOnRequest > 0 && deleteObjectsBatchSizes.size() == failDeleteObjectsOnRequest) {
            List<MultiObjectDeleteException.DeleteError> errors = new ArrayList<>();
            for (DeleteObjectsRequest.KeyVersion kv : req.getKeys()) {
                MultiObjectDeleteException.DeleteError err = new MultiObjectDeleteException.DeleteError();
                err.setKey(kv.getKey());
                err.setVersionId(kv.getVersion());
                err.setCode("InternalError");
                err.setMessage("injected failure");
                errors.add(err);
            }
            throw new MultiObjectDeleteException(errors, Collections.<DeleteObjectsResult.DeletedObject>emptyList());
        }
        List<DeleteObjectsResult.DeletedObject> deleted = new ArrayList<>();
        for (DeleteObjectsRequest.KeyVersion kv : req.getKeys()) {
            List<S3VersionSummary> vs = versions.get(kv.getKey());
            if (vs != null) vs.removeIf(s -> s.getVersionId().equals(kv.getVersion()));
            if (vs != null && vs.isEmpty()) versions.remove(kv.getKey());
            DeleteObjectsResult.DeletedObject d = new DeleteObjectsResult.DeletedObject();
            d.setKey(kv.getKey());
            d.setVersionId(kv.getVersion());
            deleted.add(d);
        }
        return new DeleteObjectsResult(deleted);
    }

    @Override
    public CopyObjectResult copyObject(CopyObjectRequest req) {
        log.add("COPY " + req.getSourceKey() + " -> " + req.getDestinationKey());
        CopyObjectResult r = new CopyObjectResult();
        r.setETag("copied");
        return r;
    }

    @Override
    public AccessControlList getObjectAcl(String b, String k, String v) {
        return new AccessControlList();
    }

    @Override
    public AccessControlList getObjectAcl(String b, String k) {
        return new AccessControlList();
    }

    @Override
    public void shutdown() {
    }

    static AmazonS3Exception notFound() {
        AmazonS3Exception e = new AmazonS3Exception("Not Found");
        e.setStatusCode(404);
        return e;
    }

    static String md5Hex(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** convenience for building request-log assertions */
    public List<String> logMatching(Function<String, Boolean> predicate) {
        List<String> out = new ArrayList<>();
        for (String l : log) if (predicate.apply(l)) out.add(l);
        return out;
    }
}
