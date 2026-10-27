# Partner implementation (imported)

Working copy of the Chisel design from partner commit `56ceb00`.

The local adaptation:

- removes unused `chiseltest`, `coursier`, and `commons-io` imports that
  are not available in the `v0` build;
- keeps the partner package structure so it can be reviewed side by side
  with `v0/reference/partner-56ceb00`;
- is selected by `cpu.Generate` as the course-facing `student_top`.

The imported revision is still an in-progress draft. Compilation currently
fails because module interfaces, configuration imports, and several signal
names are inconsistent. This directory is therefore not yet ready for
simulation or submission.
