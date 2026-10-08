# Install and test the community JAR

This is an unofficial test build of ecs-sync 3.5.5 with the islatest-v2 correction and fixes 0001–0005. It still prints `EcsSync v3.5.5`; identify it by its SHA-256, not its version string.

- File: `ecs-sync-3.5.5-v2+fix0001-0005-COMBINED.jar`
- Size: 53,273,184 bytes
- SHA-256: `49f11d693e066dd7018ea8f3acbfd79e7525f6ba3cc63a41bba2f696a5c8b943`

These commands apply to an **existing ecs-sync 3.5.5 installation** on a Linux system using `ecs-sync.service`, `/opt/emc/ecs-sync/lib/ecs-sync.jar` as a symlink, and the `com.emc.ecs.sync.EcsSyncCli` launcher with its REST API on `http://localhost:9200`. This installation method was used on a Rocky Linux host. Confirm your unit and launcher use this layout before continuing. Different layouts need adapted commands.

Keep your Java, service, UI, database configuration, and existing JARs. These instructions install a distinct file and record the actual previous symlink and checksum for rollback. They do not use the diagnostic or deployment helpers from the investigation kit.

## 1. Prepare

Download the complete JAR into `/tmp/` on the ecs-sync host. This public package supplies the whole JAR; reassembly is not needed.

Let all copy jobs finish, confirm there are no active jobs in the UI, and pause scheduling or other submissions for the entire switch and readiness check. The commands below do **not** inspect the scheduler or active-job list. Also confirm the existing service and UI are working before the change.

Run the following block from an account with sudo access. Change the variables at the top only if your verified installation requires it. The block refuses to overwrite an existing file with the new JAR's name.

```bash
sudo bash <<'BASH'
set -euo pipefail
umask 077

LIB=/opt/emc/ecs-sync/lib
SERVICE=ecs-sync
NEW=ecs-sync-3.5.5-v2+fix0001-0005-COMBINED.jar
DOWNLOAD=/tmp/ecs-sync-3.5.5-v2+fix0001-0005-COMBINED.jar
EXPECTED=49f11d693e066dd7018ea8f3acbfd79e7525f6ba3cc63a41bba2f696a5c8b943
REST_URL=http://localhost:9200/host
STATE_ROOT=/var/lib/ecs-sync-hotfix

die() { echo "STOP: $*" >&2; exit 1; }
for command in readlink sha256sum java curl pgrep systemctl; do
    command -v "$command" >/dev/null || die "Missing command: $command"
done
test -L "$LIB/ecs-sync.jar" || die "ecs-sync.jar is not a symlink"
systemctl is-active --quiet "$SERVICE" || die "Existing service is not active"
curl -fsS --max-time 10 "$REST_URL" >/dev/null || die "Existing REST API is unavailable"
PREVIOUS_LINK=$(readlink "$LIB/ecs-sync.jar")
PREVIOUS_REAL=$(readlink -f "$LIB/ecs-sync.jar")
test -f "$PREVIOUS_REAL" || die "Previous JAR cannot be resolved"
test ! -e "$LIB/$NEW" && test ! -L "$LIB/$NEW" || die "New filename already exists; do not overwrite it"
printf '%s  %s\n' "$EXPECTED" "$DOWNLOAD" | sha256sum -c -

mkdir -p "$STATE_ROOT"
chmod 700 "$STATE_ROOT"
STATE=$(mktemp -d "$STATE_ROOT/install-XXXXXXXX")
printf '%s\n' "$PREVIOUS_LINK" > "$STATE/previous-link"
printf '%s\n' "$PREVIOUS_REAL" > "$STATE/previous-path"
sha256sum "$PREVIOUS_REAL" > "$STATE/previous.sha256"
printf '%s\n' "$LIB/$NEW" > "$STATE/installed-path"
echo "SAVE THIS ROLLBACK RECORD: $STATE"

cp "$DOWNLOAD" "$LIB/$NEW"
chown --reference="$PREVIOUS_REAL" "$LIB/$NEW"
chmod --reference="$PREVIOUS_REAL" "$LIB/$NEW"
if command -v selinuxenabled >/dev/null && selinuxenabled; then
    restorecon -v "$LIB/$NEW"
fi
printf '%s  %s\n' "$EXPECTED" "$LIB/$NEW" | sha256sum -c -
java -jar "$LIB/$NEW" --version

systemctl stop "$SERVICE"
if pgrep -f 'com[.]emc[.]ecs[.]sync[.]EcsSyncCli' >/dev/null; then
    die "An engine JVM remains. Symlink has not been changed."
fi
test "$(readlink "$LIB/ecs-sync.jar")" = "$PREVIOUS_LINK" || die "Symlink changed during preparation"
ln -sfn "$NEW" "$LIB/ecs-sync.jar"
printf '%s  %s\n' "$EXPECTED" "$LIB/ecs-sync.jar" | sha256sum -c -
systemctl start "$SERVICE"

READY=0
for attempt in {1..60}; do
    if systemctl is-active --quiet "$SERVICE" &&
       curl -fsS --max-time 3 "$REST_URL" >/dev/null 2>&1; then
        READY=1
        break
    fi
    sleep 2
done
test "$READY" = 1 || die "Service/REST did not become ready. Use rollback below; record: $STATE"
mapfile -t ENGINE_PIDS < <(pgrep -f 'com[.]emc[.]ecs[.]sync[.]EcsSyncCli')
test "${#ENGINE_PIDS[@]}" = 1 || die "Expected exactly one engine JVM; check manually before testing"
OPEN_JAR=0
for fd in /proc/"${ENGINE_PIDS[0]}"/fd/*; do
    if test "$(readlink -f "$fd" 2>/dev/null || true)" = "$LIB/$NEW"; then
        printf '%s  %s\n' "$EXPECTED" "$fd" | sha256sum -c -
        OPEN_JAR=1
        break
    fi
done
test "$OPEN_JAR" = 1 || die "Could not confirm the running JVM opened the new JAR. Check before testing."
ls -lZ "$LIB/ecs-sync.jar" "$LIB/$NEW"
echo "READY: new JAR is running and REST responds. Rollback record: $STATE"
BASH
```

Do not continue after a `STOP` message or another failed command. The block does **not** automatically roll back. If a failure occurred before the symlink switch, the link remains unchanged; the service may already have been stopped. Save the printed rollback-record directory before closing the terminal. Check that the existing UI responds before submitting a test.

## 2. Run a controlled copy

Use a **fresh, empty, versioned destination bucket** and a new job with a fresh tracking table/database. Use read-only source credentials and disable source deletion and source write tests. Keep `includeVersions=true` and `verify=true`. Match the other options to the workload being tested.

Keep the source application quiescent during the test. For VB365 repositories, use the repository maintenance procedure. Check lifecycle or other independent bucket activity separately; application maintenance alone does not establish that the version history stayed unchanged.

Record the endpoints' product/build versions, sanitized job configuration, JAR checksum, job totals, errors, and retries. A completed job with zero failed objects and no MD5 mismatch errors is the first result to check. For independent validation, compare per-key version counts, data/delete-marker sequence, content, and current state. Retain the source, destination, and logs until validation is finished. A successful copy test does not by itself establish application-level restore usability.

## 3. Roll back

Finish or deliberately stop jobs and prevent scheduled submissions first. Rollback restarts the engine; it does not undo anything already copied to a destination bucket. Keep the combined JAR as a separate file for reference.

Set `STATE` to the exact rollback-record directory printed during installation. The recorded target can be stock 3.5.5 or a previous patched build. These commands verify that recorded file instead of assuming which one you used.

```bash
sudo bash <<'BASH'
set -euo pipefail

LIB=/opt/emc/ecs-sync/lib
SERVICE=ecs-sync
STATE=/var/lib/ecs-sync-hotfix/REPLACE_WITH_YOUR_INSTALL_DIRECTORY
REST_URL=http://localhost:9200/host

die() { echo "STOP: $*" >&2; exit 1; }
test -L "$LIB/ecs-sync.jar" || die "ecs-sync.jar is not a symlink"
test -r "$STATE/previous-link" || die "Set STATE to the saved installation record"
PREVIOUS_LINK=$(cat "$STATE/previous-link")
PREVIOUS_REAL=$(cat "$STATE/previous-path")
sha256sum -c "$STATE/previous.sha256"

systemctl stop "$SERVICE"
if pgrep -f 'com[.]emc[.]ecs[.]sync[.]EcsSyncCli' >/dev/null; then
    die "An engine JVM remains. Symlink has not been changed."
fi
ln -sfn "$PREVIOUS_LINK" "$LIB/ecs-sync.jar"
test "$(readlink -f "$LIB/ecs-sync.jar")" = "$PREVIOUS_REAL" || die "Rollback link does not resolve to recorded JAR"
sha256sum -c "$STATE/previous.sha256"
systemctl start "$SERVICE"

READY=0
for attempt in {1..60}; do
    if systemctl is-active --quiet "$SERVICE" &&
       curl -fsS --max-time 3 "$REST_URL" >/dev/null 2>&1; then
        READY=1
        break
    fi
    sleep 2
done
test "$READY" = 1 || die "Previous link restored, but service/REST is not ready; inspect the local service log"
mapfile -t ENGINE_PIDS < <(pgrep -f 'com[.]emc[.]ecs[.]sync[.]EcsSyncCli')
test "${#ENGINE_PIDS[@]}" = 1 || die "Expected exactly one engine JVM; verify manually"
OPEN_JAR=0
for fd in /proc/"${ENGINE_PIDS[0]}"/fd/*; do
    if test "$(readlink -f "$fd" 2>/dev/null || true)" = "$PREVIOUS_REAL"; then
        OPEN_JAR=1
        break
    fi
done
test "$OPEN_JAR" = 1 || die "Could not confirm the running JVM opened the recorded previous JAR"
echo "ROLLBACK READY: recorded previous JAR is running and REST responds"
BASH
```

Check the UI before resuming normal jobs or schedules. If collecting logs for an issue, inspect and remove credentials and private object/bucket names before posting them publicly.
