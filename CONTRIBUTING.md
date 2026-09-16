## How To Contribute
 
#### Basics

Pull requests and bug reports are welcomed.  For enhancement pull requests, please ask first to save time!  It's possible the proposed enhancement is outside the scope or design goals of the project.

#### Local Installation

First configure `SOKLET_JAVADOC_HOME` as described under
[Reproducible Javadoc and Packaging](#reproducible-javadoc-and-packaging).

```shell
$ mvn -Dgpg.skip=true install
```

This will test and build unsigned development artifacts and install them to your
local Maven repository. Use `mvn -Dgpg.skip=true verify` to check the build without
installing it. Signing and publication are separate, explicitly authorized steps.

#### Continuous Integration

CI builds on JDK 17, 21, and 25 against the exact Soklet commit pinned in
`.github/workflows/ci.yml`. For a manual run, `soklet_ref` accepts a full lowercase
40-character commit SHA, not a branch, tag, or abbreviated SHA. Update both the
manual default and automatic fallback together when selecting a new candidate.

Before installing core, CI verifies that the checkout matches the requested SHA
and that its POM version matches this project's declared `soklet.version` baseline.
The selected commit and coordinates appear in the build log. Tests and Javadoc
use the dependency declared in `pom.xml`, without overriding it for an arbitrary
checkout. These checks validate a candidate; they do not publish either project.

After committing core changes, the project owner must update both candidate
defaults to that reviewed, retrievable commit and obtain a green matrix. An
existing pin does not validate later uncommitted core changes; integrations that
use newer core APIs may fail until the owner advances that pin.

#### Reproducible Javadoc and Packaging

Compilation and runtime compatibility still target Java 17. Documentation uses
the separately pinned Corretto **26.0.2.11.1** generator (runtime `26.0.2.1`).
Before any Maven command that packages Javadoc, including `verify` or `install`,
set `SOKLET_JAVADOC_HOME` to that JDK's home directory; keep `JAVA_HOME` on the
JDK used to compile/test. `mvn test` does not require a documentation JDK.

```sh
export SOKLET_JAVADOC_HOME=/absolute/path/to/corretto-26.0.2.11.1
```

CI downloads and checksum-verifies the exact Linux documentation JDK without
changing the test matrix's Java runtime. Canonical release builds compile on
JDK 17 and invoke this JDK 26 Javadoc executable; rebuilds must match both pins.

External package indexes are checked in under `src/main/javadoc/links`, with
source archive and index checksums in `manifest.json`. Check them with
`node scripts/verify-javadoc-links.mjs`. Dependency upgrades must update the
matching versioned index and link target together.

For a release candidate, unpack the **exact core candidate's packaged Javadoc
JAR** into a dedicated directory and pass that directory to the build:

```shell
mvn -Dgpg.skip=true -Dsoklet.javadoc.location=/absolute/path/to/core-javadoc clean verify
```

This supplies the core package index locally while generated hyperlinks still
point to `https://javadoc.soklet.com/`. Without this override, ordinary local
builds use the deployed core index; that fallback must not be used for an
unreleased candidate. CI always uses the checked-out core candidate's Javadoc
JAR. The JAR timestamp, module name, implementation version, and bundled license
are set in the POM/resources; compare rebuilds using the same JDK and inputs.

#### Publishing to Maven Central

Contact Mark Allen at mark@revetware.com to request publishing access for the `com.soklet` namespace. Generate a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/) and configure its generated username and password in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central-portal</id>
      <username>YOUR_TOKEN_USERNAME</username>
      <password>YOUR_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

The server ID must match the `central-publishing-maven-plugin` configuration in `pom.xml`. Before an explicitly owner-approved release, unlock the exact approved signing key through `gpg-agent` in an interactive session. Never put a GPG passphrase in command arguments, environment variables, Maven settings, project files, logs, or release evidence.

Build and sign the complete artifact set locally:

```shell
mvn clean verify
```

Confirm that the versioned main JAR, sources JAR, Javadocs JAR, and their signatures were produced under `target/`. The GPG plugin also signs the project POM for deployment. Then upload the release bundle:

```shell
mvn clean deploy
```

The current Central plugin configuration waits for the uploaded deployment to validate but does not publish it automatically. Review the validated deployment in the [Central Publisher Portal](https://central.sonatype.com/publishing/deployments), then select **Publish**. Published coordinates are immutable, so verify the version and artifacts before completing that step.
