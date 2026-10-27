# Case study — rebuilding a village quarter

A quarter of six houses on a live Paper 26.1.2 server, rebuilt with BuildKit and verified
before anyone walked through it. The full Russian working report is in
[`CASE-STUDY-RU.md`](CASE-STUDY-RU.md); this is the short version.

World: `english_server`, overworld `world`, region `r.-1.-2`, ground level `y = 63`.
Work window: 00:09–02:47 (+07).

## What changed

| Parameter | Before | After |
|---|---|---|
| Quarter footprint | 37 × 39 | 53 × 37 |
| Street width | 4 blocks | 6 blocks |
| Gaps between houses | 2–3 blocks | 8–9 blocks |
| Plaza with a well | none | 10 × 10, well in the centre |
| Exits | 6 porches, no paths | 6 porches + paths to the street |
| West junction | cliff edge | 6-block funnel onto the village road |
| East end of the street | ran into jungle | 8 × 8 viewing platform at the water, railing, lantern, bench |
| Greenery | plain grass | 11 trees, bushes, grass and ferns along the rim |

Houses keep their structure files; only positions and door sides changed, and every block
state — `facing`, `half`, `shape`, bed `part`, wall connections — was transformed by the
server's own `BlockData.rotate/mirror`, not by hand-written tables.

## How it was verified

1. **Blocks.** `verify.py` compared the world against the structure NBT block by block:
   `3942/3942 (100.00%)`, zero name mismatches, zero property mismatches.
2. **Walkability.** `/bk walkcheck` over six doors and two through-routes: `8/8 passable`.
3. **Roofs.** Every house cell has a roof block above it: `6/6`.
4. **Street and plaza.** Zero defective cells, zero obstacles (well and benches counted as
   objects, not obstructions).
5. **Paths.** 6/6 continuous from porch to street, no obstacles.
6. **Surroundings.** No water above ground level, no vegetation on paths, terrain flattened
   to `y = 63`, rim of grass and ferns.

Result: `qa.py` PASS.

## Build log

| Script | Commands | Blocks | Time | Min TPS |
|---|---|---|---|---|
| `city.bk` — clearing, street, plaza, greenery | 809 | 10 401 | 0.7 s | 19.15 |
| `fix_exits.bk` — funnel, platform, foliage | 101 | 322 | 0.0 s | 20.00 |
| `fix_beds.bk` — beds | 8 | 8 | 0.1 s | 20.00 |

Each run left an automatic snapshot (`auto-*.bk`), so `/bk undo 1|2|3` could roll back any
step without touching the other two.

## Left open

- The world spawn at `(-262, 70, -814)` and its WorldGuard region were not touched — it sits
  200 blocks west of the quarter and is a separate task with its own acceptance criteria.
- The viewing platform is the intended end of the street; a bridge or a descent to the water
  was not built.
