# FB-SMP shop / sell / loan upgrade — implementation report

Scope: `FB-SMP_Shop_Sell_Loan_Master_Specification.pdf`.
Build entry point: `bash _staging/build.sh <gradle-args>`.

## Status

| Phase | Item | State |
|-------|------|-------|
| A | Read-only inspection + report | done |
| B1 | Common pure logic (calendar, interest, timeout tiers) | done |
| B2 | Pricing engine, validator, catalog, sell policy | done |
| B3 | `/shop` catalog GUI + buy pipeline | done |
| B4 | `/sell` via `SellService` + policy, sell-all preview | done |
| B5 | `/loan` service, V4 migration, scheduler, admin/audit | done |
| C | Offline tests | done |
| D | Staging docs | this file |

## Build and test

- `:fbsmp-common` and `:fbsmp-paper` compile.
- Tests: **57 total, 0 failures** (49 common + 8 paper DB integration).

## V4 migration (`V4__shop_sell_loan_upgrade.sql`)

Additive only. Adds loan calendar columns, `loan_events`, `loan_timeouts`
(UNIQUE on `loan_id, overdue_event_id`), `loan_meta`, `loan_notices`,
`fbsmp_audit_log`.

Validated against a WAL-safe copy of the live DB
(`_staging/fb-smp-upgrade/<ts>/db-backups/`):
- `PRAGMA integrity_check` = ok
- existing player/loan rows preserved
- timeout UNIQUE guard confirmed

No live DB was modified.

## B4 — /sell

`SellMenu` previously priced items from `sell_prices.yml` only and the
`SellService`/`SellEligibilityPolicy`/`SellPreview` classes were unused.

Now:
- `SellService` is constructed in `FbsmpPlugin` from the `ShopMenu` price
  registry and the default `SellEligibilityPolicy`.
- Manual staging uses `SellService.offer(...)`; refusals show the policy
  message (renamed / lore / enchanted / custom-model / container / durability).
- New **Sell all sellables** button (slot 48) stages every eligible stack up to
  the 45-line capacity, then prints a `SellPreview` (totals + kept reasons).
- Removed the dead `priceFor(Material)` and its `sell_prices.yml` reader.
  Pricing now comes from the same registry as `/shop`, so `shop.yml` anchors and
  `sell_prices.yml` overrides both apply through `ShopPricing`.

Escrow is unchanged: `beginSellSession` / `addSellItem` / `prepareSellCommit` /
`finishSellCommit` / `refundSellSession`, with `recoverSellSessions` on join.

## B5 — /loan

`LoanService` (new, `paper/loan/`):
- Calendar-day terms via `FbsmpCalendar`; interest via `InterestCalculator`.
- Warning at `warning-lead-days` before due; overdue transition at due day;
  tiered timeouts from `loans.yml` (`timeout.tiers`), clamped by
  `timeout.max-duration-seconds`.
- Idempotent: one warning per loan, one overdue event per loan, one timeout per
  overdue event, release-on-clear.
- Offline players get persisted `loan_notices` delivered on next join.
- 1-minute async scheduler tick in `FbsmpPlugin`.

Commands:
- `/loan take <amount>`, `/loan repay [amount]`, `/loan info` (alias `vu`).
- `/fbsmp loan list|info|forgive|settle|adjust|releasetimeouts`.
- `/fbsmp audit` reads `fbsmp_audit_log`.

Timeout gate: borrowing is blocked while a timeout is active unless the player
has `fbsmp.loan.limit.bypass`.

## Config

`loans.yml` gains `warning-lead-days`, `require-clear-timeout-to-borrow`,
`interest.model`/`interest.tiers`, and `timeout.max-duration-seconds`/`timeout.tiers`.
Defaults reproduce the previous 8% / 30-day behaviour.

## Rollback

- Code: revert the branch; no destructive schema change.
- DB: restore `_staging/fb-smp-upgrade/<ts>/db-backups/fbsmp-before-v4.sqlite`.
  V4 is additive, so an older plugin build tolerates the extra columns/tables.
