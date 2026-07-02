# Jarvis Klippy

This repository composes the Jarvis and Clippy projects without coupling their
standalone applications.

- `auth/` is the unchanged shared authentication project owned by this repository.
- `klippy/` is the Clippy Git submodule containing the clipboard applications.
- `eyes-and-ears/` is the Jarvis Git submodule containing bucket services and
  screen/audio recorder clients.

Initialize both application submodules after cloning:

```bash
git submodule update --init --recursive
```

Auth intentionally is not a submodule. Both application repositories resolve
the shared Maven modules from their parent sibling directory, `../auth`.

## Build compositions

JDK 25 and Maven 3.9 or newer are required. The default reactor builds every
Java module:

```bash
mvn package
```

Focused compositions are selected with Maven profiles:

```bash
mvn -Pklippy package
mvn -Peyes-and-ears package
mvn -Pclients package
mvn -Pservers package
```

Every Maven application can still be built as a standalone artifact. Select
the application by artifact ID and let Maven include only its dependencies:

```bash
mvn -Pclients -pl :clippy-linux-client -am package
mvn -Pservers -pl :clippy-server -am package
mvn -pl :auth-server -am package
mvn -Pservers -pl :bucket-proxy -am package
```

The focused subtree reactors are also available:

```bash
mvn package
```

The Android project and the `eyes-and-ears/clients` shell clients keep their
own standalone build and deployment instructions.

## Composition model

Executable modules remain thin deployment boundaries. Reusable Klippy client
behavior lives in `klippy/clients/client-core`, configuration and auth session
handling live in its `dev.clippy.clients.core.env` package, and authentication contracts live
in `auth/api` and `auth/client`. A future combined desktop client should depend
on those libraries and provide its own launcher rather than invoking an existing
standalone launcher.

The current eyes-and-ears recorder clients are shell applications, so they are
composable at deployment/process level today. Their recorder and uploader logic
should be extracted behind library interfaces before a single-process Java
client embeds those capabilities.

For Klippy Docker deployments, run Compose from `klippy/` as documented in
[`klippy/README.md`](klippy/README.md).
