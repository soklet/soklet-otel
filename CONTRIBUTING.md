## How To Contribute
 
#### Basics

Pull requests and bug reports are welcomed.  For enhancement pull requests, please ask first to save time!  It's possible the proposed enhancement is outside the scope or design goals of the project.

#### Local Installation

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

The server ID must match the `central-publishing-maven-plugin` configuration in `pom.xml`. Before uploading a release, either prime `gpg-agent` in an interactive session or securely export the GPG passphrase through the Maven GPG plugin's default `MAVEN_GPG_PASSPHRASE` environment variable. Do not put the passphrase directly in shell history or project files.

Build and sign the complete artifact set locally:

```shell
mvn clean verify
```

Confirm that the versioned main JAR, sources JAR, Javadocs JAR, and their signatures were produced under `target/`. The GPG plugin also signs the project POM for deployment. Then upload the release bundle:

```shell
mvn clean deploy
```

The current Central plugin configuration waits for the uploaded deployment to validate but does not publish it automatically. Review the validated deployment in the [Central Publisher Portal](https://central.sonatype.com/publishing/deployments), then select **Publish**. Published coordinates are immutable, so verify the version and artifacts before completing that step.
