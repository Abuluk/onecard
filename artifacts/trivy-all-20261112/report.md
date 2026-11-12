# Phase 1 Application Image Scan

- Date: 2026-11-12.
- Target: `admin1@192.168.0.41`, Docker Engine 29.6.2, Linux amd64.
- Build: all nine images were built without cache from the current workspace's JDK 25 application JARs and the pinned Temurin 25 Alpine base digest. Each image's `/app/app.jar` SHA-256 matched its local JAR.
- Scanner: Trivy 0.76.2, image digest `sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb`.
- Vulnerability DB: schema 2, updated `2026-11-12 01:03:48 UTC`, downloaded `2026-11-12 05:40:12 UTC`.
- Java DB: schema 1, updated `2026-11-12 00:58:04 UTC`, downloaded `2026-11-12 05:48:59 UTC`.
- Scan scope: OS and Java packages, `HIGH,CRITICAL`, unfixed findings included. The first cold-cache run reached Trivy's default 5-minute scan timeout while analyzing the transaction image; after the databases were cached, all final scans completed with a 30-minute timeout. The CI scan action now sets `TRIVY_TIMEOUT=30m`.

## Results

| Service | Image ID | Local JAR SHA-256 | OS H/C | Java H/C |
|---|---|---|---:|---:|
| gateway | `sha256:ea0af61b5d853b4f767caf93aa63ffb8d0d1baa583d9fb07f75cb1ded723d270` | `0e05446491c61ae1f59cb20e846eac8c493514a3e4faedc721e58226fce52485` | 0/0 | 1/0 |
| account | `sha256:c1b31b92e911ef6fc27cc1325bd32848b4547613cca5f9cea9a871653d18f84b` | `be099942e29bdf3b683554d6f3a2f715ddd338f52222a6b7f0b82eeb53888ac3` | 0/0 | 1/0 |
| transaction | `sha256:f2489a21c8165d151e9d51d6f08235f7ff3f67e107a7f49192abd945e3a5f90f` | `a273f88b7708147764532cf1d96d31b126085d504fac3ae001a945b9a05c5f91` | 0/0 | 1/0 |
| payment-adapter | `sha256:736a886f5e0e3a696c72b104d9dc9a10b8be9319d87a6a51a6534a41cff46373` | `5057d11a8e0db81e951400617f554634998828f415e8ffc184c3d7df2818c25f` | 0/0 | 1/0 |
| clearing | `sha256:2318e325e869211cd874856ca5c540521af6690aed0cc80bbeccbf4ff3e32966` | `65c861a0c4dce0df838a70339f2f9a404fb6a37b75e7f5ec828ddee97950b452` | 0/0 | 1/0 |
| notification | `sha256:34b18062d29b4cd3704af629c556a59fd5ea520897e9f8ccc813891ad1bd8591` | `f1ca0a2666cd557b703cb0dbc8e99698608af0775b997c688dbc65f9a73fa9fb` | 0/0 | 1/0 |
| risk | `sha256:8fd2e4bc6d30c6dd5de507bed5fd4cc93e5f4ce80dc3ac30d1d3815da1a1cd9f` | `d7849d4eca137aac5ee3e0f8ca3f11aa6f254cffb89cb9182a4a074e0f4a6547` | 0/0 | 1/0 |
| access | `sha256:3b411b871e348d54190b3a24f1832acb64270e31ceca42927b71f7f5a49e52b5` | `93e363d078e82a216fc64584070194cef3a4dfa38c2e3e72ab99cc7269357101` | 0/0 | 1/0 |
| device | `sha256:6a158f80e815ad5e5e7313345a635bafd2d38f7db3bb86ebc552dab4b9f07ab3` | `f71ef4daba3b76f4ae7439e959fc20a54dbeb38553347ce77a4334714057b143` | 0/0 | 1/0 |

All raw JSON reports are in this directory. Every image has the same HIGH finding: `CVE-2026-102554` in `com.google.guava:guava:33.3.1-android`, embedded in `BOOT-INF/lib/nacos-client-3.1.1.jar`; Trivy lists `33.7.2` as the fixed version. No CRITICAL findings or OS HIGH/CRITICAL findings were detected. The current Nacos server baseline remains 3.1.1. The previously checked `nacos-client` 3.1.2 and 3.3.0 artifacts still contain affected Guava 33.5.0-android, so neither is treated as a fix. No suppression was added.

The security gate result is **failed**: nine HIGH findings remain. Image-by-image JSON is filtered to HIGH/CRITICAL to match the blocking CI gate. The remote scan images, Trivy image, cache volume and temporary report directory are temporary and are removed after the reports are copied into this repository.
