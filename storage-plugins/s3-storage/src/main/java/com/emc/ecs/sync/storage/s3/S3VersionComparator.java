/*
 * Copyright (c) 2016-2021 Dell Inc. or its subsidiaries. All Rights Reserved.
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
 * Community modifications, 2026-10-08: ecs-sync v3.5.5-community.1; see MODIFICATIONS.md. */
package com.emc.ecs.sync.storage.s3;

import java.util.Comparator;

public class S3VersionComparator implements Comparator<S3ObjectVersion> {
    @Override
    public int compare(S3ObjectVersion o1, S3ObjectVersion o2) {
        // the current version (IsLatest) always sorts last: it is the end of the version chain by definition.
        // ordering by mtime alone can place a non-current version after it (mtime ties broken by the arbitrary
        // version-id string, or storage-preserved mtimes), which makes loadObject() pick the wrong version as
        // current, putIntermediateVersions() consume its stream before the current-version put re-reads it
        // ("Stream is closed"), and the aggregate version checksum differ between source and target.
        if (o1.isLatest() != o2.isLatest()) return o1.isLatest() ? 1 : -1;
        int result = o1.getMetadata().getModificationTime().compareTo(o2.getMetadata().getModificationTime());
        if (result == 0) result = o1.getVersionId().compareTo(o2.getVersionId());
        return result;
    }
}
