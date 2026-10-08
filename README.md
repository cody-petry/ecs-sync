<!-- Community modification notice, 2026-10-08: added fork introduction; upstream README retained below. -->
# Community fork: ecs-sync

This branch contains **ecs-sync 3.5.5 Community Hotfix 1**, an unofficial test build with corrections for S3 version ordering, verification, retries, pagination, and version deletion.

Start with the [community documentation](community-hotfix/README.md) for the fix list, validation record, build instructions, and installation/rollback procedure. Release label: `v3.5.5-community.1`. Compiled release assets are pending publication on the [Releases page](https://github.com/cody-petry/ecs-sync/releases).

Original project documentation follows.

---

ecs-sync
=========

ecs-sync is a bulk copy utility that can move data between various systems in parallel

For more information, please see the [wiki](https://github.com/EMCECS/ecs-sync/wiki)

Dependency Updates
=========

To check for updated dependency versions across all modules, use the [gradle-versions-plugin](https://github.com/ben-manes/gradle-versions-plugin):
```shell
./gradlew dependencyUpdates
```