# Third-Party Licenses

This directory contains the license texts for third-party libraries used by EDDI.

## EDDI License

EDDI itself is licensed under the **Apache License 2.0** — see [../LICENSE](../LICENSE).

## Third-Party License Types

The following license types are used by EDDI's dependencies:

| File | SPDX Identifier | Used By (examples) |
|------|-----------------|---------------------|
| [MIT.txt](MIT.txt) | MIT | langchain4j, SLF4J, Mockito |
| [BSD-2-Clause.txt](BSD-2-Clause.txt) | BSD-2-Clause | jzlib |
| [BSD-3-Clause.txt](BSD-3-Clause.txt) | BSD-3-Clause | ASM, Protocol Buffers |
| [EPL-1.0.txt](EPL-1.0.txt) | EPL-1.0 | JUnit 4 (legacy) |
| [EPL-2.0.txt](EPL-2.0.txt) | EPL-2.0 | Jakarta EE, Eclipse Vert.x |
| [LGPL-2.1.txt](LGPL-2.1.txt) | LGPL-2.1-only | Various (linked only) |
| [LGPL-3.0.txt](LGPL-3.0.txt) | LGPL-3.0-or-later | Various (linked only) |
| [GPL-2.0-with-classpath-exception.txt](GPL-2.0-with-classpath-exception.txt) | GPL-2.0 WITH Classpath-exception-2.0 | OpenJDK-derived libraries |
| [CDDL-1.0.txt](CDDL-1.0.txt) | CDDL-1.0 | javax/jakarta APIs (dual-licensed) |
| [CC0-1.0.txt](CC0-1.0.txt) | CC0-1.0 | Public domain dedications |
| [UPL-1.0.txt](UPL-1.0.txt) | UPL-1.0 | Oracle OCI GenAI SDK |
| [EDL-1.0.txt](EDL-1.0.txt) | BSD-3-Clause (EDL variant) | Eclipse Distribution |
| [ISC.txt](ISC.txt) | ISC | jBCrypt |

## Generating a Full Dependency Report

To generate a complete list of all dependencies and their licenses, run:

```bash
./mvnw package -Plicense-gen -DskipTests
```

This produces:
- `licenses/THIRD-PARTY.txt` — full dependency listing
- `licenses/third-party/` — downloaded license texts
- `licenses/licenses.xml` — machine-readable license inventory

## Note

These are plain-text license files used for compliance documentation.
They replace the previous HTML snapshots from opensource.org which
caused false-positive security scanner alerts (the HTML pages included
CDN references to Bootstrap 3.4.1, which has known XSS vulnerabilities
CVE-2024-6485 and CVE-2025-1647 — neither of which affected EDDI since
Bootstrap was never an actual dependency).
