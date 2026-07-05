# Eyes and Ears

This repository contains the Jarvis bucket services and macOS/Linux recorder
clients. It is mounted as `eyes-and-ears/` by the composition repository.

Authentication is supplied by the parent repository's sibling `../auth`
directory. Build the Java services from the composition root or directly from
this checkout while it is mounted under that parent:

```bash
mvn package
```

The recorder clients under `clients/` remain standalone shell applications.

### Syncer (`clients/syncer`)

The syncer drains completed recording segments to the bucket proxy. It merges older completed segments (via ffmpeg concat) and uploads the result, leaving the current in-progress segment untouched. Run it on a timer:

```bash
bash clients/syncer/syncer.sh
```

Configure proxy credentials in `clients/syncer/config.sh`.
