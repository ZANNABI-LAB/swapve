# Releasing to Maven Central

> ⚠️ **Central can never delete a version once it is released.** That is why half of this document
> is rehearsal. The release itself is the last two lines.

## What is published, and what is not

| Module | Coordinates | Why |
|---|---|---|
| `ocpp-core` | `io.github.zannabi-lab:ocpp-core` | The framework-agnostic codec, schema, and session layers. **The thing someone else can depend on** |
| `swap-domain` | `io.github.zannabi-lab:swap-domain` | The domain model, with zero dependencies |

`csms` · `station-sim` · `sim-console` are applications and `java-compat` is test-only, so none of
them has a reason to own coordinates. The configuration lives in one place: `publishedModules` in
the root `build.gradle.kts`.

## Status

**The namespace `io.github.zannabi-lab` is registered, and `0.0.1` was released on 2026-08-21.**

> How the namespace was obtained is worth recording. Central does **not** register organization
> namespaces automatically — the Portal only grants `io.github.<username>` for the GitHub username
> the account signed up with ([official docs](https://central.sonatype.org/register/namespace/)):
>
> > *"Currently, we only support the GitHub username that you used to sign up, so
> > `io.github.<github organization name>` is not available as an automatically registered
> > namespace."*
>
> The route that works is *Namespaces → Add Namespace* in the Portal, which issues a verification
> key; you create a public repository with that key as its name under the organization and press
> *Verify*. (The empty repository can be deleted afterwards.) If that is blocked, mail
> <central-support@sonatype.com> asking for manual verification of organization ownership.
>
> Changing the group ID later is **one line in `gradle.properties`** — the module boundary checks
> reference `rootProject.group` rather than hardcoding coordinates, so they follow along.

## Prerequisites

1. **A Central Portal account** — sign in with GitHub at <https://central.sonatype.com>
2. **A registered namespace** — see above
3. **A GPG key** — Central requires every artifact to be signed

   ```bash
   gpg --gen-key                                   # name and email
   gpg --list-secret-keys --keyid-format=short     # find the key ID
   gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>   # publish the public key (required)
   gpg --armor --export-secret-keys <KEY_ID>       # the value for gradle.properties below
   ```

   > In an environment with no `/dev/tty` (agents, CI, some remote shells) `gpg --gen-key` dies
   > with *"cannot open '/dev/tty'"*. Use batch mode there —
   > `gpg --batch --pinentry-mode loopback --gen-key <parameter-file>`, with `Key-Type`,
   > `Name-Real`, `Name-Email`, and `Passphrase` in the file.
   > **The passphrase sits in that file in plain text, so delete it afterwards.**

   Keep the revocation certificate that `gpg --gen-key` writes to
   `~/.gnupg/openpgp-revocs.d/<FINGERPRINT>.rev` **somewhere off this machine**. It is the only way
   to invalidate the key if it is lost or leaked.

4. **A Portal token** — generated in the Portal UI. It is not your account password
5. **`~/.gradle/gradle.properties`** (in your home directory, not in the repository):

   ```properties
   mavenCentralUsername=<Portal token username>
   mavenCentralPassword=<Portal token password>
   signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nlQdG...\n-----END PGP PRIVATE KEY BLOCK-----
   signingInMemoryKeyId=<KEY_ID>
   signingInMemoryKeyPassword=<key passphrase>
   ```

   ⚠️ **`signingInMemoryKey` takes the whole ASCII-armored key on one line**, with newlines written
   as `\n` — a `.properties` file turns those back into newlines. **Do not strip the header and
   footer.** Stripping them makes `signMavenPublication` die with *"Could not read PGP secret key"*
   (observed for real in the 2026-08-21 rehearsal; this document used to give the wrong
   instruction).

   ```bash
   # produces exactly the value to paste
   gpg --armor --export-secret-keys <KEY_ID> | python3 -c \
     "import sys;print(sys.stdin.read().strip().replace(chr(10), chr(92)+'n'))"
   ```

   Narrow the file permissions with `chmod 600`.

## Rehearsal — this is where problems get caught

### 1. Publish to the local repository

```bash
./gradlew :ocpp-core:publishToMavenLocal :swap-domain:publishToMavenLocal
```

This passes even with no signing key (`signMavenPublication SKIPPED`), because the root build sets
`signing.required = false` where no key exists. **The release path does not take that relaxation** —
Central rejects an unsigned bundle, so a forgotten key is caught at upload.

### 2. Inspect the artifacts

```bash
cd ~/.m2/repository/io/github/zannabi-lab/ocpp-core/<version>
unzip -l ocpp-core-<version>.jar | grep -c 'ocpp/schemas/.*\.json'   # → 181
unzip -l ocpp-core-<version>.jar | grep 'META-INF/NOTICE'            # → must be there
cat ocpp-core-<version>.pom
```

Three things must be present:

- **The 181 official schemas** — without them schema validation dies wholesale on the consumer's side
- **`META-INF/LICENSE` and `META-INF/NOTICE`** — Apache-2.0 §4(d), and more importantly the jar
  contains **the OCA schemas, which are CC BY-ND 4.0**. Someone who took only the coordinates can
  see that notice nowhere else. *(They were genuinely missing in the first rehearsal.)*
- **`name`, `description`, `url`, `licenses`, `developers`, `scm` in the POM** — Central rejects the
  bundle if any is absent

### 3. Compile it as a consumer would

**This is the core of the rehearsal.** Create a minimal project in a separate directory and resolve
from `mavenLocal()`.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral() } }

// build.gradle.kts
plugins { kotlin("jvm") version "2.1.0"; application }
dependencies {
    implementation("io.github.zannabi-lab:ocpp-core:<version>")
    implementation("io.github.zannabi-lab:swap-domain:<version>")
}
```

Three things to confirm:

1. **Codec round-trip** — `OcppFrameCodec().encode(...)` → `decode(...)` comes back as `Decoded`
2. **Schema validation** — `OcppPayloadValidator().validateCall("BootNotification", payload)` gives
   `Valid`, and an empty payload gives `Invalid`. **That is the evidence that the schemas inside the
   jar really load**
3. **Call it from Java too** — `new OcppFrameCodec(new ObjectMapper())`. This re-confirms the
   verdict of [LAYERS §4](LAYERS.md) against the actual published artifact

> **Expect this**: the consumer's console prints `SLF4J(W): No SLF4J providers were found`, because
> `json-schema-validator` uses `slf4j-api`. Adding any implementation removes it. It has no effect
> on behaviour.

### 4. After the release, repeat step 3 against the real thing

Once the release has propagated, delete the local copy and resolve **without `mavenLocal()`**, so
nothing can leak in from `~/.m2`:

```bash
rm -rf ~/.m2/repository/io/github/zannabi-lab
# settings.gradle.kts → repositories { mavenCentral() }   (mavenLocal deliberately omitted)
```

## Releasing

```bash
./gradlew publishToMavenCentral            # upload only — a human releases it in the Portal
./gradlew publishAndReleaseToMavenCentral  # upload and release automatically
```

**Always use the first form for a first release.** It is better to keep the step where a person
looks at the file list and the POM once more and then presses the button, because it cannot be
undone.

The version is one line in `gradle.properties`. The plan was **`0.0.1` first to walk the whole
path, then `0.1.0`** — `0.0.1` remaining is not a cost, it is a record.

Watch it land:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  https://repo1.maven.org/maven2/io/github/zannabi-lab/ocpp-core/<version>/ocpp-core-<version>.pom
```

Propagation to `repo1.maven.org` takes minutes to tens of minutes after the Portal reports the
release; the search index takes longer still.

## After a release

- Replace the dependency coordinates in `README.md` and `README.ko.md`
- Add the version to [`CHANGELOG.md`](../CHANGELOG.md)
