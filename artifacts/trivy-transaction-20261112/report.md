# Transaction Image Vulnerability Scan

- Target: `admin1@192.168.0.41` (Docker Engine 29.6.2).
- Scanner: Trivy 0.76.2; vulnerability and Java databases downloaded from the `docker.1panel.live/aquasec` mirrors and cached in a temporary volume.
- Image: `onecard-phase1-transaction-scan:20261112-03734da9e8`, image ID `sha256:e55a07c01a6bd62d9cce150dfeee81f85cc93cc3a44e86065f9335463541b865`; built without cache from the current transaction-service JAR.
- Result: Alpine 3.24.4 has 0 HIGH/CRITICAL findings. The application JAR has 1 HIGH and 0 CRITICAL findings.
- Finding: `CVE-2026-102554`, `com.google.guava:guava:33.3.1-android` embedded and relocated inside `nacos-client:3.1.1`; fixed version is 33.7.2. NVD describes resource exhaustion during Java deserialization of `CompactHashMap`, `CompactHashSet`, or `MapMakerInternalMap`.
- Compatibility check: Maven Central `nacos-client:3.1.2` embeds Guava `33.5.0-android`; latest stable `nacos-client:3.3.0` also embeds `33.5.0-android` and the same vulnerable classes. Neither is a verified fix. The Nacos server remains at the selected 3.1.1 baseline.
- Raw Trivy JSON: [`transaction-image.json`](transaction-image.json).
- This scan confirms a blocking finding; it is not a passing scan and no exception was added.
