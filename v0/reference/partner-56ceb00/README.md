# Partner source snapshot

This directory contains the top-level Chisel sources provided by partner at:

- repository: `git@github.com:suisdavid/CPU.git`
- commit: `56ceb004d3fec975d5c33a937f12bef32b6453da`
- commit subject: `ugly first draft`
- import date: 2026-10-27

Only the top-level `*.scala` files and `notes.md` from
`partner/CPU/CPU-chisel` are included. Generated RTL, build products,
editor metadata, test-run directories, the course framework, and Git
metadata are intentionally excluded.

This is the unmodified upstream snapshot. The working copy under
`v0/src/main/scala/partner` removes dependencies on external test
utilities and is wired to the local sbt build. It is not yet a compiling
CPU implementation.
