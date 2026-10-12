# Live smoke test — FB-SMP shop/sell/loan upgrade

Date: 2026-10-11
Backend: Paper 26.1.2 on JDK 25 (`/workspace/jdk25/jdk-25.0.4.1+1`), `127.0.0.1:25566`, `online-mode=false`.

## Purpose

Exercise the new plugin build on the real server: confirm it loads, runs the
V4 migration against the live database, keeps the data, and does not throw from
the loan scheduler. A Minecraft client is not available in this environment, so
GUI interactions (`/shop`, `/sell`) were not clicked through; their logic is
covered by the unit tests.

## Steps and results

1. WAL-safe backup of the live DB via `sqlite3.Connection.backup`.
   - `_staging/fb-smp-upgrade/smoke-20261011-134048/live-before-smoke.sqlite`
   - sha256 `6ac4cbca0a68958c5190184881420c1aa9cb9472b022600b445a1c9af2c58c65`
   - pre-state: migrations `{1,2,3}`, integrity ok, 1 player, 1 loan.
2. Backed up the deployed jar and `plugins/FBSMP/loans.yml`; deployed the new
   jar (sha256 `854a8b53…`) and the new `loans.yml`.
3. Started Paper; watched startup.

| Check | Result |
|-------|--------|
| Plugin load | `[FBSMP] Enabling FBSMP v1.0.0` — no errors |
| Plugin enable message | "Database migrations are current; Paper backend remains the data authority." |
| Server ready | `Done (11.077s)!` |
| FBSMP exceptions at runtime | none |
| Scheduler exceptions | none (ran ~70s) |

4. Live DB after startup:

| Check | Result |
|-------|--------|
| `PRAGMA integrity_check` | ok |
| migrations | `{1,2,3,4}` |
| players / loans | 1 / 1 (preserved) |
| new loan columns | `issue_day_index`, `due_day_index`, `term_days`, `original_remaining_minor`, `warning_state`, `warning_sent_for_day`, `overdue_event_id`, `forgiven`, `settled`, `created_calendar_date` |
| new tables | `loan_events`, `loan_timeouts`, `loan_meta`, `loan_notices`, `fbsmp_audit_log` |

5. Stopped the server and restored the environment: original jar
   (sha256 `cc0c14d6…`), original `loans.yml`, and the pre-smoke DB
   (sha256 `6ac4cbca…`, re-verified).

## Notes

- Paper logged three `DirectoryProviderSource` errors for `fbsmp-velocity-1.0.0.jar`,
  `floodgate-velocity.jar` and `Geyser-Velocity.jar` in `plugins/`. These are
  Velocity plugins placed in the Paper backend folder; they are pre-existing and
  unrelated to this change. (They belong in the Velocity `plugins/` folder.)
- The environment is left exactly as found: the live DB is back to V3 and the
  original plugin jar is deployed.

## Conclusion

The upgrade build loads and enables on the real Paper backend and migrates a copy
of the live database to V4 without data loss. Deploying the new jar to production
would perform the same additive migration on the live DB.
