# Releasing to Maven Central

Releases are built by `.github/workflows/release.yml` when a `v*` tag is pushed. The version in the POMs on `main` stays `x.y.z-SNAPSHOT`; the workflow sets the release version from the tag.

## One-time setup

1. **Namespace.** In the [Central Portal](https://central.sonatype.com) → Namespaces, `io.github.mihirraj` must say *Verified*. To verify it, create a public GitHub repo named after the verification key, click *Verify Namespace*, then delete the repo.

2. **User token.** Central Portal → your account → *Generate User Token*. It shows a username and a password; keep both.

3. **GPG signing key** (Git Bash has `gpg`):

   ```bash
   gpg --full-generate-key          # RSA and RSA, 4096 bits, no expiry or a long one, a strong passphrase
   gpg --list-secret-keys --keyid-format long   # the key id is after "rsa4096/"
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # Central checks signatures against public keyservers
   gpg --armor --export-secret-keys <KEY_ID>                   # the private key, for the GitHub secret below
   ```

   Keep a backup of the key outside this machine. Losing it means future releases are signed with a different key.

4. **GitHub secrets.** Repository → Settings → Secrets and variables → Actions → *New repository secret*:

   | Secret | Value |
   |---|---|
   | `CENTRAL_USERNAME` | user token username (step 2) |
   | `CENTRAL_TOKEN` | user token password (step 2) |
   | `GPG_PRIVATE_KEY` | the whole output of `--export-secret-keys`, including the BEGIN/END lines |
   | `GPG_PASSPHRASE` | the key's passphrase |

## Each release

1. Update `CHANGELOG.md` (move *unreleased* to the version and date) and the version in the README's install snippets. Commit to `main`.
2. Tag and push:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. Watch the *Release* workflow in the Actions tab. It runs the full test suite, then uploads and waits for Central's validation.
4. Central Portal → *Deployments*: the deployment shows *Validated*. Check the file list, then click **Publish**. It appears on Maven Central within about 30 minutes, and in search a few hours later.
5. Create a GitHub release for the tag with the changelog entry.

A release cannot be changed or deleted once published. If something is wrong before step 4, click *Drop* in the portal, fix it, delete the tag (`git push --delete origin v0.1.0`, `git tag -d v0.1.0`) and tag again.

## Trying the release build locally

Without uploading or signing:

```bash
./mvnw -Prelease -Dgpg.skip -DskipTests verify
```
