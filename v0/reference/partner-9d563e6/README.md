# Partner source snapshot

This directory contains an exact, non-compiled snapshot of the Chisel files
that partner provided at:

- repository: `git@github.com:suisdavid/CPU.git`
- commit: `9d563e6de75d82bbccc1c2dcc1049dd66dd02ec7`
- commit subject: `finished ALU and BRU`
- import date: 2026-10-26

Only the top-level `*.scala` files from `partner/CPU/CPU-chisel` were copied.
Partner build products, editor metadata, generated RTL, test run directories, the
course framework, and the partner Git repository were intentionally excluded.

The files here are reference material and are outside sbt's default
`src/main/scala` source tree, so they do not participate in the `v0` build. They
also mix Chisel and C++ and are known to be incomplete.

The maintained implementation lives under `v0/src/main/scala/cpu/core`. Files
are ported there only after they compile under the `v0` toolchain and their
interfaces have been made internally consistent.
