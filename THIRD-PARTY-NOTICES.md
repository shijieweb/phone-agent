# Third-party notices

This repository ships **no third-party dependency**. `app/build.gradle.kts` has
exactly one entry in `dependencies` — `testImplementation junit:junit:4.13.2` —
and `gradle/libs.versions.toml` keeps its `[libraries]` block empty on purpose.

What it does contain is **vendored source**: files copied into
`app/src/main/java/`, compiled into the APK, and maintained here. Vendoring was
chosen over a Maven/JitPack dependency deliberately: a library that updates
itself is a library nobody reviewed, and this one holds the private key that
authorises a shell on the phone.

Nothing is fetched at build time. There is no git submodule, no Gradle plugin
that downloads code, and no binary blob.

---

## 1. libadb — ADB protocol client

- **Upstream**: `Muntashirakon/libadb`, version 3.1.1
- **Local source**: `app/src/main/java/io/github/muntashirakon/adb/`
  (including the `android/` subpackage: `AdbMdns`, `AndroidUtils`)
- **Licence**: dual `GPL-3.0-or-later OR Apache-2.0`; **this project takes
  Apache-2.0**
- **Files**: 19 original `.java` files, each keeping its own SPDX header
  (`// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0`).

### Local changes to the vendored source

Every change is marked with a comment saying *why*, per this repo's rule that
comments explain reasons rather than restate code.

| File | Change | Reason |
|---|---|---|
| `AndroidPubkey.java` | `org.bouncycastle.util.encoders.Base64` → `android.util.Base64`, with `NO_WRAP` | Remove the BouncyCastle dependency. `NO_WRAP` matters: BC folded the output at 76 chars, and `adbkey.pub` must be one line |
| `PairingAuthCtx.java` | `SHA256Digest`/`HKDFBytesGenerator`/`GCMBlockCipher`/`AEADParameters`/`KeyParameter` → `MessageDigest` + JCE `AES/GCM/NoPadding` + `HkdfSha256` | Same reason; the GCM IV construction and the "no AAD" detail are preserved bit for bit |
| `SslUtils.java` | added package-private `clearSslContext()` | The `SSLContext` is cached process-wide and pins the first `KeyPair`. Without a reset, `shkey regen apply` produces "pairing succeeds, connecting fails" |
| `PRNGFixes.java` | **removed** | Dead code: three self-references and no caller anywhere in the tree |
| all `import androidx.annotation.*` | → `io.github.muntashirakon.adb.annotation.*` | No AndroidX dependency. The replacements are local empty annotations with the same shapes (`@GuardedBy` takes a `String`, `@RequiresApi` an `int`, `@IntDef`/`@StringDef` arrays) |

## 2. spake2-java — SPAKE2 pairing and the curves it needs

- **Upstream**: `Muntashirakon/spake2-java`
- **Local source**: `app/src/main/java/io/github/muntashirakon/crypto/`
  (`spake2/`, `ed25519/`, `x25519/` — 12 files)
- **Licence**: **LGPL-3.0**. Each file keeps its original header
  (`Copyright (C) 2021 Muntashir Al-Islam` / `Licensed according to the LICENSE
  file in this repository`).
- **Files are unmodified.** They are compiled into the APK as-is; the LGPL
  relinking requirement is met by the source being present in this repository,
  in its original package, with no obfuscation (`isMinifyEnabled = false`).

## 3. Code written here, in the vendored packages

These live under the same directory tree but are this project's own work (MIT,
see [LICENSE](LICENSE)):

| File | Why it exists |
|---|---|
| `crypto/HkdfSha256.java` | RFC 5869 HKDF-SHA256, ~30 lines, replacing BouncyCastle's `HKDFBytesGenerator`. Pinned by the official test vectors in `HkdfSha256Test` |
| `adb/AdbKeyCodec.java` | A deliberate narrow bridge: `AndroidPubkey` and `SslUtils` are package-private, and widening vendored code just to reach it would be worse than one small, reviewable door |
| `adb/annotation/*.java` | The nine empty annotations listed above |

The self-signed X.509 certificate (`SelfAdbKeys.selfSigned`, hand-written DER)
and the RSA key generation are this project's own code as well — Android exposes
no certificate-issuing API, and ADB only requires that the key and certificate
match.

---

## Provenance

The vendored trees were copied from a local checkout at
`D:/phonectl/vendor-src/` (`la/` = libadb, `sp2/` = spake2-java). That checkout
carried no `LICENSE`/`COPYING` file, so the licence identifiers above come from
each file's own SPDX header and from the upstream repositories. If a future
maintainer needs the exact upstream commits, they are the only fact this file
does not pin — everything else is byte-identical to the source in this repo.
