# Agent instructions

## Commits

Make regular, focused commits as you work — don't let a finished feature sit uncommitted
while you start the next one.

- One concern per commit: a feature, a fix, or a refactor, not several mixed together.
  If a change spans several concerns, split it into separate commits (hunk-level staging
  is fine when a file is touched by more than one).
- Keep every commit self-contained: it should compile and pass unit tests on its own, not
  only as part of the final tree.
- Write commit messages in the existing style: a short imperative summary with a topic
  prefix, e.g. `Detector v19: extension veto for boundary-complete winners`.
- Push after each logical milestone so work is never stranded locally.
