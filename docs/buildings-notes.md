# Buildings ingest — the two findings

Recorded here because the commit that introduced them
(`46d5e06`) lost three phrases to shell backtick substitution, and the
history is already pushed. Rewriting it would need a force-push, which
this workspace forbids.

## 1. A clean run exited 1

`(or bad (not (:ok? m)))` where `bad` is `0` returns `0`, and **`0` is
truthy in ClojureScript**. A run of 9 tiles and 944 buildings with zero
failures therefore exited 1. The check is `(pos? bad)`.

## 2. `measure-max-zoom` reported no basemap at all

It probed with `HEAD`. Cloudflare's REST object API answers `HEAD` with a
non-2xx, so every object read as absent and the function concluded there
was no basemap — in a bucket holding 1,365 tiles. It now uses a one-byte
ranged `GET`.

## Not done: a size filter on the ground polygons

One central Manhattan tile holds **512 `grass` rings**, and each costs a
triangulation in the browser. The filter belongs at ingest. It is not
there because the cost has not been measured, and dropping data to fix a
slowness nobody has seen is how a map loses its parks. `:surface-count`
travels with every tile so the decision can be made from a number.

## The commit-message lesson

Write commit messages through a **file** (`git commit -F msg.txt`) or a
quoted heredoc, never through an interpolated shell string. Backticks in
prose about code are command substitution to the shell: they vanish
silently and leave a sentence that still reads as a sentence.
