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

import com.emc.object.s3.S3Client;
import com.emc.object.s3.S3Exception;
import com.emc.object.s3.S3ObjectMetadata;
import com.emc.object.s3.bean.*;
import com.emc.object.s3.request.DeleteObjectRequest;
import com.emc.object.s3.request.DeleteObjectsRequest;
import com.emc.object.s3.request.GetObjectMetadataRequest;
import com.emc.object.s3.request.GetObjectRequest;
import com.emc.object.s3.request.ListObjectsRequest;
import com.emc.object.s3.request.ListVersionsRequest;
import com.emc.object.s3.request.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * Minimal in-memory versioned-bucket implementation of the Dell object-client {@link S3Client} interface (via a dynamic
 * proxy) for offline unit tests of {@link EcsS3Storage}. Mirrors {@link InMemoryAmazonS3}: versions are kept in listing
 * order (newest first); real pagination for listVersions/listMoreVersions and listObjects/listMoreObjects; scripted
 * version and object pages; deleteObjects request sizes are recorded and the n-th request can be made to fail.
 * Methods that a test does not need throw {@link UnsupportedOperationException} so that unexpected calls are visible.
 */
public class InMemoryEcsS3Client implements InvocationHandler {
    final String bucket;
    /** key -> versions in listing order (newest first) */
    final Map<String, List<AbstractVersion>> versions = new TreeMap<>();
    final List<Integer> deleteObjectsBatchSizes = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    int failDeleteObjectsOnRequest = 0;
    int pageSize = 1000;
    final Deque<ListVersionsResult> scriptedVersionPages = new ArrayDeque<>();
    final Deque<ListObjectsResult> scriptedObjectPages = new ArrayDeque<>();
    int versionCounter = 0;
    /** key#versionId -> content of data versions (only for versions added with content or written via putObject) */
    final Map<String, byte[]> data = new HashMap<>();
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

    public InMemoryEcsS3Client(String bucket) {
        this.bucket = bucket;
    }

    public S3Client proxy() {
        return (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class}, this);
    }

    // ------------------------------------------------------------------ fixture helpers

    public AbstractVersion addVersion(String key, String versionId, String etag, boolean latest, boolean deleteMarker, Date mtime, long size) {
        AbstractVersion v;
        if (deleteMarker) {
            v = new DeleteMarker();
        } else {
            Version dv = new Version();
            dv.setETag(etag);
            dv.setSize(size);
            v = dv;
        }
        v.setKey(key);
        v.setVersionId(versionId);
        v.setLatest(latest);
        v.setLastModified(mtime);
        versions.computeIfAbsent(key, k -> new ArrayList<>()).add(0, v); // newest first
        return v;
    }

    /** adds a data version with content (size and ETag derived from the content) */
    public AbstractVersion addVersion(String key, String versionId, boolean latest, Date mtime, byte[] content) {
        AbstractVersion v = addVersion(key, versionId, InMemoryAmazonS3.md5Hex(content), latest, false, mtime, content.length);
        data.put(key + "#" + versionId, content);
        return v;
    }

    public ListVersionsResult scriptVersionPage(boolean truncated, String nextKeyMarker, String nextVersionIdMarker, boolean deleteMarkers, Date mtime, String... keys) {
        ListVersionsResult page = new ListVersionsResult();
        page.setBucketName(bucket);
        page.setMaxKeys(pageSize);
        page.setTruncated(truncated);
        page.setNextKeyMarker(nextKeyMarker);
        page.setNextVersionIdMarker(nextVersionIdMarker);
        List<AbstractVersion> list = new ArrayList<>();
        int i = 0;
        for (String key : keys) {
            AbstractVersion v;
            if (deleteMarkers) v = new DeleteMarker();
            else {
                Version dv = new Version();
                dv.setETag("00000000000000000000000000000001");
                dv.setSize(1L);
                v = dv;
            }
            v.setKey(key);
            v.setVersionId("v" + (++i));
            v.setLatest(true);
            v.setLastModified(mtime);
            list.add(v);
        }
        page.setVersions(list);
        scriptedVersionPages.add(page);
        return page;
    }

    public ListObjectsResult scriptObjectPage(boolean truncated, String nextMarker, String... keys) {
        ListObjectsResult page = new ListObjectsResult();
        page.setBucketName(bucket);
        page.setMaxKeys(pageSize);
        page.setTruncated(truncated);
        page.setNextMarker(nextMarker);
        List<S3Object> list = new ArrayList<>();
        for (String key : keys) {
            S3Object o = new S3Object();
            o.setKey(key);
            o.setSize(1L);
            o.setETag("00000000000000000000000000000001");
            o.setLastModified(new Date(0));
            list.add(o);
        }
        page.setObjects(list);
        scriptedObjectPages.add(page);
        return page;
    }

    public List<String> versionIds(String key) {
        List<String> ids = new ArrayList<>();
        List<AbstractVersion> vs = versions.get(key);
        if (vs != null) for (AbstractVersion v : vs) ids.add(v.getVersionId());
        return ids;
    }

    public int countRequests(String prefix) {
        int n = 0;
        for (String l : log) if (l.startsWith(prefix)) n++;
        return n;
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "listVersions":
                if (args[0] instanceof ListVersionsRequest) return listVersions((ListVersionsRequest) args[0]);
                break;
            case "listMoreVersions":
                return listMoreVersions((ListVersionsResult) args[0]);
            case "listObjects":
                if (args[0] instanceof ListObjectsRequest) return listObjects((ListObjectsRequest) args[0]);
                break;
            case "listMoreObjects":
                return listMoreObjects((ListObjectsResult) args[0]);
            case "getObjectMetadata":
                if (args[0] instanceof GetObjectMetadataRequest) return getObjectMetadata((GetObjectMetadataRequest) args[0]);
                if (args.length == 2) return getObjectMetadata(new GetObjectMetadataRequest((String) args[0], (String) args[1]));
                break;
            case "deleteObjects":
                return deleteObjects((DeleteObjectsRequest) args[0]);
            case "getObject":
                if (args.length == 2 && args[0] instanceof GetObjectRequest) return getObject((GetObjectRequest<?>) args[0]);
                break;
            case "putObject":
                if (args[0] instanceof PutObjectRequest) return putObject((PutObjectRequest) args[0]);
                break;
            case "deleteObject":
                if (args.length == 2) {
                    deleteObject((String) args[1]);
                    return null;
                }
                if (args.length == 1 && args[0] instanceof DeleteObjectRequest) {
                    deleteObject(((DeleteObjectRequest) args[0]).getKey());
                    return null;
                }
                break;
            case "getObjectAcl":
                return new AccessControlList();
            case "shutdown":
            case "destroy":
                return null;
            case "toString":
                return "InMemoryEcsS3Client(" + bucket + ")";
            case "hashCode":
                return System.identityHashCode(this);
            case "equals":
                return proxy == args[0];
            default:
                break;
        }
        throw new UnsupportedOperationException("InMemoryEcsS3Client does not implement " + method);
    }

    // ------------------------------------------------------------------ listing

    private List<AbstractVersion> allVersions(String prefix) {
        List<AbstractVersion> out = new ArrayList<>();
        for (Map.Entry<String, List<AbstractVersion>> e : versions.entrySet()) {
            if (prefix != null && !e.getKey().startsWith(prefix)) continue;
            out.addAll(e.getValue());
        }
        return out;
    }

    private ListVersionsResult versionPage(String prefix, String delimiter, String keyMarker, String versionIdMarker) {
        List<AbstractVersion> all = allVersions(prefix);
        int start = 0;
        if (keyMarker != null) {
            for (int i = 0; i < all.size(); i++) {
                AbstractVersion v = all.get(i);
                if (v.getKey().equals(keyMarker) && (versionIdMarker == null || v.getVersionId().equals(versionIdMarker))) {
                    start = i + 1;
                    break;
                }
            }
        }
        ListVersionsResult l = new ListVersionsResult();
        l.setBucketName(bucket);
        l.setPrefix(prefix);
        l.setDelimiter(delimiter);
        l.setMaxKeys(pageSize);
        l.setKeyMarker(keyMarker);
        l.setVersionIdMarker(versionIdMarker);
        int end = Math.min(start + pageSize, all.size());
        l.setVersions(new ArrayList<>(all.subList(start, end)));
        if (end < all.size()) {
            l.setTruncated(true);
            AbstractVersion last = all.get(end - 1);
            l.setNextKeyMarker(last.getKey());
            l.setNextVersionIdMarker(last.getVersionId());
        } else {
            l.setTruncated(false);
        }
        return l;
    }

    ListVersionsResult listVersions(ListVersionsRequest req) {
        log.add("listVersions prefix=" + req.getPrefix() + " delimiter=" + req.getDelimiter() + " keyMarker=" + req.getKeyMarker());
        if (!scriptedVersionPages.isEmpty()) return scriptedVersionPages.poll();
        return versionPage(req.getPrefix(), req.getDelimiter(), req.getKeyMarker(), req.getVersionIdMarker());
    }

    ListVersionsResult listMoreVersions(ListVersionsResult previous) {
        log.add("listMoreVersions keyMarker=" + previous.getNextKeyMarker() + " versionIdMarker=" + previous.getNextVersionIdMarker());
        if (!scriptedVersionPages.isEmpty()) return scriptedVersionPages.poll();
        return versionPage(previous.getPrefix(), previous.getDelimiter(), previous.getNextKeyMarker(), previous.getNextVersionIdMarker());
    }

    private ListObjectsResult objectPage(String prefix, String marker) {
        List<S3Object> all = new ArrayList<>();
        for (Map.Entry<String, List<AbstractVersion>> e : versions.entrySet()) {
            if (prefix != null && !e.getKey().startsWith(prefix)) continue;
            AbstractVersion latest = e.getValue().get(0);
            if (latest instanceof DeleteMarker) continue;
            S3Object o = new S3Object();
            o.setKey(e.getKey());
            o.setSize(((Version) latest).getSize());
            o.setETag(((Version) latest).getETag());
            o.setLastModified(latest.getLastModified());
            all.add(o);
        }
        int start = 0;
        if (marker != null) for (int i = 0; i < all.size(); i++) if (all.get(i).getKey().equals(marker)) start = i + 1;
        ListObjectsResult l = new ListObjectsResult();
        l.setBucketName(bucket);
        l.setPrefix(prefix);
        l.setMaxKeys(pageSize);
        l.setMarker(marker);
        int end = Math.min(start + pageSize, all.size());
        l.setObjects(new ArrayList<>(all.subList(start, end)));
        if (end < all.size()) {
            l.setTruncated(true);
            l.setNextMarker(all.get(end - 1).getKey());
        }
        return l;
    }

    ListObjectsResult listObjects(ListObjectsRequest req) {
        log.add("listObjects prefix=" + req.getPrefix() + " marker=" + req.getMarker());
        if (!scriptedObjectPages.isEmpty()) return scriptedObjectPages.poll();
        return objectPage(req.getPrefix(), req.getMarker());
    }

    ListObjectsResult listMoreObjects(ListObjectsResult previous) {
        log.add("listMoreObjects marker=" + previous.getNextMarker());
        if (!scriptedObjectPages.isEmpty()) return scriptedObjectPages.poll();
        return objectPage(previous.getPrefix(), previous.getNextMarker());
    }

    // ------------------------------------------------------------------ object operations

    S3ObjectMetadata getObjectMetadata(GetObjectMetadataRequest req) {
        log.add("HEAD " + req.getKey() + " v=" + req.getVersionId());
        List<AbstractVersion> vs = versions.get(req.getKey());
        if (vs == null) throw new S3Exception("Not Found", 404);
        AbstractVersion match = null;
        for (AbstractVersion v : vs) {
            if (req.getVersionId() == null ? v.isLatest() : v.getVersionId().equals(req.getVersionId())) {
                match = v;
                break;
            }
        }
        if (match == null || match instanceof DeleteMarker) throw new S3Exception("Not Found", 404);
        S3ObjectMetadata om = new S3ObjectMetadata();
        om.setContentLength(((Version) match).getSize());
        om.setLastModified(new Date(match.getLastModified().getTime() / 1000 * 1000)); // HEAD has second precision
        om.setETag(((Version) match).getETag());
        om.setContentType("application/octet-stream");
        om.setUserMetadata(new HashMap<>());
        return om;
    }

    GetObjectResult<InputStream> getObject(GetObjectRequest<?> req) {
        byte[] d = data.get(req.getKey() + "#" + req.getVersionId());
        if (d == null) throw new S3Exception("Not Found", 404);
        log.add("GET " + req.getKey() + " v=" + req.getVersionId());
        GetObjectResult<InputStream> r = new GetObjectResult<>();
        r.setObject(new ByteArrayInputStream(d));
        return r;
    }

    PutObjectResult putObject(PutObjectRequest req) {
        try {
            Object entity = req.getObject();
            byte[] content;
            if (entity instanceof InputStream) {
                InputStream in = (InputStream) entity;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close(); // the Jersey client closes the request entity stream after sending it
                content = bos.toByteArray();
            } else if (entity instanceof byte[]) {
                content = (byte[]) entity;
            } else {
                throw new UnsupportedOperationException("InMemoryEcsS3Client cannot store a " + (entity == null ? null : entity.getClass()));
            }
            String vid = nextVersionId("tv");
            log.add("PUT " + req.getKey() + " bytes=" + content.length + " -> " + vid);
            List<AbstractVersion> vs = versions.get(req.getKey());
            if (vs != null) for (AbstractVersion v : vs) v.setLatest(false);
            addVersion(req.getKey(), vid, true, now(), content);
            PutObjectResult result = new PutObjectResult();
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("ETag", Collections.singletonList("\"" + InMemoryAmazonS3.md5Hex(content) + "\""));
            headers.put("x-amz-version-id", Collections.singletonList(vid));
            result.setHeaders(headers);
            return result;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    void deleteObject(String key) {
        String vid = nextVersionId("dm");
        log.add("DELETE " + key + " -> delete marker " + vid);
        List<AbstractVersion> vs = versions.get(key);
        if (vs != null) for (AbstractVersion v : vs) v.setLatest(false);
        addVersion(key, vid, null, true, true, now(), 0);
    }

    DeleteObjectsResult deleteObjects(DeleteObjectsRequest req) {
        List<ObjectKey> keys = req.getDeleteObjects().getKeys();
        deleteObjectsBatchSizes.add(keys.size());
        log.add("DeleteObjects keys=" + keys.size());
        if (failDeleteObjectsOnRequest > 0 && deleteObjectsBatchSizes.size() == failDeleteObjectsOnRequest) {
            throw new S3Exception("injected failure", 500);
        }
        List<AbstractDeleteResult> results = new ArrayList<>();
        for (ObjectKey k : keys) {
            List<AbstractVersion> vs = versions.get(k.getKey());
            if (vs != null) vs.removeIf(v -> v.getVersionId().equals(k.getVersionId()));
            if (vs != null && vs.isEmpty()) versions.remove(k.getKey());
            DeleteSuccess s = new DeleteSuccess();
            s.setKey(k.getKey());
            s.setVersionId(k.getVersionId());
            results.add(s);
        }
        DeleteObjectsResult r = new DeleteObjectsResult();
        r.setResults(results);
        return r;
    }
}
