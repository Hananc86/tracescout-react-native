# Release scripts

`prepublish-check.py` is the gate. It reads the packed file list from
`npm pack --dry-run` -- the artifact a customer receives, not the working tree
-- and fails if any file carries a decorative badge, a reference to internal
development process, a maintainer name, a competitor name, a private host or
filesystem path, an ingest credential or a credential-shaped literal.

```sh
python3 scripts/prepublish-check.py .
```

`cleanup.py` applies the comment rewrites. It edits comment spans only and
asserts that the comment-stripped source is token-identical before and after,
so a rule that escaped its span fails the run instead of changing behaviour.
`comments.py` is the comment-span walker it uses. `string-exceptions.py` holds
the short audited list of diagnostic message strings rewritten outside that
guarantee; the typed reasons beside them are never touched.
