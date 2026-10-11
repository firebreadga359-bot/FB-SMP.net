package net.firebreadmc.fbsmp.paper.loan;

import net.firebreadmc.fbsmp.common.FbsmpCalendar;
import net.firebreadmc.fbsmp.common.InterestCalculator;
import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.common.TimeoutTierSelector;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loan lifecycle: issuance, warning, overdue, timeout and repayment.
 *
 * <p>Correctness model: calendar days are the source of truth, not wall-clock
 * timers, so restarts and skipped ticks cannot double-process a day. Every step is
 * idempotent:
 * <ul>
 *   <li>a warning is sent at most once per loan (guarded by {@code warning_state});</li>
 *   <li>an overdue transition fires once per loan;</li>
 *   <li>a timeout is created at most once per overdue event
 *       ({@code UNIQUE(loan_id, overdue_event_id)});</li>
 *   <li>repayment releases any blocking timeout exactly once.</li>
 * </ul>
 * Offline players receive notices from the persisted {@code loan_notices} table on
 * their next join.
 */
public final class LoanService {

    public record LoanTerms(long principalMinor, long interestMinor, long totalMinor,
                            long issueDayIndex, long dueDayIndex, int termDays) {}

    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final FbsmpCalendar calendar;
    private final InterestCalculator interestCalculator;
    private final TimeoutTierSelector timeoutTiers;
    private final int dayLengthMinutes;
    private final long calendarStartMillis;
    private final int warningLeadDays;
    private final int repaymentDays;
    private final long maximumOutstanding;
    private final boolean requireClearTimeoutToBorrow;

    public LoanService(FbsmpPlugin plugin, FbsmpDatabase database, long calendarStartMillis)
            throws SQLException {
        this.plugin = plugin;
        this.database = database;
        this.calendarStartMillis = calendarStartMillis;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "loans.yml"));
        this.dayLengthMinutes = plugin.getConfig().getInt("calendar.day-length-minutes", 30);
        this.calendar = FbsmpCalendar.fromConfig(
                config.getInt("regular-month-days", 30),
                config.getInt("special-month-days", 29));
        this.warningLeadDays = Math.max(1, config.getInt("warning-lead-days", 3));
        this.maximumOutstanding = Money.parseMinorUnits(config.getString("maximum-principal", "1000000.00"));
        this.requireClearTimeoutToBorrow = config.getBoolean("require-clear-timeout-to-borrow", true);

        int defaultTerm = Math.max(1, config.getInt("repayment-days", 30));
        this.repaymentDays = defaultTerm;
        InterestCalculator.Model model = "TIERED".equalsIgnoreCase(config.getString("interest.model", "LINEAR"))
                ? InterestCalculator.Model.TIERED : InterestCalculator.Model.LINEAR;
        BigDecimal baseRate = BigDecimal.valueOf(config.getDouble("interest-rate-percent", 8.0))
                .movePointLeft(2);
        List<InterestCalculator.Tier> tiers = new ArrayList<>();
        ConfigurationSection tierSection = config.getConfigurationSection("interest.tiers");
        if (tierSection != null) {
            for (String key : tierSection.getKeys(false)) {
                tiers.add(new InterestCalculator.Tier(Integer.parseInt(key),
                        BigDecimal.valueOf(tierSection.getDouble(key)).movePointLeft(2)));
            }
        }
        this.interestCalculator = new InterestCalculator(new InterestCalculator.Config(
                model, baseRate, BigDecimal.ZERO, baseRate, java.math.RoundingMode.HALF_UP, 0, tiers));

        this.timeoutTiers = new TimeoutTierSelector(readTimeoutTiers(config), 
                config.getLong("timeout.max-duration-seconds", 604800));
    }

    private static List<TimeoutTierSelector.Tier> readTimeoutTiers(YamlConfiguration config) {
        List<TimeoutTierSelector.Tier> tiers = new ArrayList<>();
        List<?> raw = config.getList("timeout.tiers");
        if (raw != null) {
            for (Object entry : raw) {
                if (entry instanceof java.util.Map<?, ?> untyped) {
                    java.util.Map<Object, Object> map = new java.util.HashMap<>(untyped);
                    long min = minor(map.get("min-debt"));
                    Object maxRaw = map.get("max-debt");
                    Long max = maxRaw == null ? null : minor(maxRaw);
                    Object durationRaw = map.get("duration-seconds");
                    long seconds = durationRaw instanceof Number number ? number.longValue() : 3600L;
                    Object reasonRaw = map.get("reason");
                    String reason = reasonRaw == null ? "Overdue debt" : reasonRaw.toString();
                    tiers.add(new TimeoutTierSelector.Tier(min, max, seconds, reason));
                }
            }
        }
        if (tiers.isEmpty()) {
            tiers.add(new TimeoutTierSelector.Tier(1, 500_000L, 3600, "Small overdue balance"));
            tiers.add(new TimeoutTierSelector.Tier(500_001L, 2_500_000L, 86_400, "Medium overdue balance"));
            tiers.add(new TimeoutTierSelector.Tier(2_500_001L, null, 604_800, "Large overdue balance"));
        }
        return tiers;
    }

    private static long minor(Object value) {
        try {
            return new BigDecimal(String.valueOf(value)).setScale(2)
                    .movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            return 0;
        }
    }

    public FbsmpCalendar calendar() {
        return calendar;
    }

    public long maximumOutstanding() {
        return maximumOutstanding;
    }

    public long todayIndex() {
        return calendar.dayIndex(today());
    }

    public FbsmpCalendar.Date today() {
        return calendar.today(System.currentTimeMillis(), calendarStartMillis, dayLengthMinutes);
    }

    public String todayDisplay() {
        return calendar.format(today());
    }

    public LoanTerms termsFor(long principalMinor) {
        int termDays = repaymentDays;
        InterestCalculator.Result result = interestCalculator.compute(principalMinor, termDays);
        long issueDay = todayIndex();
        return new LoanTerms(principalMinor, result.interest(), result.totalDue(), issueDay,
                issueDay + termDays, termDays);
    }

    public long dueAtFor(long dueDayIndex) {
        long dayMillis = Math.max(1, dayLengthMinutes) * 60_000L;
        return calendarStartMillis + (dueDayIndex + 1) * dayMillis;
    }

    /** Issues a loan, returning the loan id, or -1 when a rule rejects it. */
    public long issue(UUID playerId, long principalMinor, boolean bypassLimits) throws SQLException {
        LoanTerms terms = termsFor(principalMinor);
        return database.issueLoanV2(playerId, principalMinor, terms.interestMinor(),
                terms.issueDayIndex(), terms.dueDayIndex(), terms.termDays(),
                todayDisplay(), maximumOutstanding, bypassLimits,
                dueAtFor(terms.dueDayIndex()), System.currentTimeMillis());
    }

    public boolean isTimedOut(UUID playerId) throws SQLException {
        return database.activeTimeout(playerId, System.currentTimeMillis()) != null;
    }

    public boolean requireClearTimeoutToBorrow() {
        return requireClearTimeoutToBorrow;
    }

    /**
     * One scheduler tick: warns, marks overdue, applies timeouts and releases
     * expired ones. Idempotent and safe to run every minute.
     */
    public void tick() throws SQLException {
        long now = System.currentTimeMillis();
        long today = todayIndex();
        database.releaseExpiredTimeouts(now);
        List<FbsmpDatabase.LoanRow> active = database.activeLoans();
        for (FbsmpDatabase.LoanRow loan : active) {
            if ("ACTIVE".equals(loan.status())) {
                long daysLeft = loan.dueDayIndex() - today;
                if (daysLeft <= warningLeadDays && daysLeft >= 0) {
                    warn(loan, daysLeft, now);
                }
                if (today >= loan.dueDayIndex()) {
                    markOverdue(loan, now);
                }
            }
        }
        // Re-read so loans marked overdue in this tick get a timeout immediately.
        for (FbsmpDatabase.LoanRow loan : database.activeLoans()) {
            if ("OVERDUE".equals(loan.status())) {
                applyTimeout(loan, now);
            }
        }
    }

    private void warn(FbsmpDatabase.LoanRow loan, long daysLeft, long now) throws SQLException {
        if (!database.markWarningSent(loan.id(), loan.dueDayIndex(), now)) {
            return;
        }
        UUID playerId = UUID.fromString(loan.playerUuid());
        String when = daysLeft == 0 ? "today" : "in " + daysLeft + " day(s)";
        String message = "Your loan #" + loan.id() + " of " + Money.format(loan.remainingMinor())
                + " is due " + when + " (" + calendar.format(calendar.fromDayIndex(loan.dueDayIndex())) + ").";
        database.addLoanNotice(playerId, loan.id(), "WARNING", message, now);
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            online.sendMessage(org.bukkit.ChatColor.GOLD + "[Loan] " + org.bukkit.ChatColor.YELLOW + message);
        }
    }

    private void markOverdue(FbsmpDatabase.LoanRow loan, long now) throws SQLException {
        long eventId = database.createLoanEvent(loan.id(), loan.playerUuid(), "OVERDUE",
                loan.dueDayIndex(), "Remaining " + loan.remainingMinor(), now);
        database.markOverdue(loan.id(), eventId, now);
        UUID playerId = UUID.fromString(loan.playerUuid());
        String message = "Your loan #" + loan.id() + " is overdue. Outstanding "
                + Money.format(loan.remainingMinor()) + ". Repay to clear your timeout.";
        database.addLoanNotice(playerId, loan.id(), "OVERDUE", message, now);
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            online.sendMessage(org.bukkit.ChatColor.RED + "[Loan] " + org.bukkit.ChatColor.YELLOW + message);
        }
    }

    private void applyTimeout(FbsmpDatabase.LoanRow loan, long now) throws SQLException {
        if (loan.overdueEventId() == null) {
            return;
        }
        UUID playerId = UUID.fromString(loan.playerUuid());
        if (database.activeTimeout(playerId, now) != null) {
            return;
        }
        TimeoutTierSelector.Tier tier = timeoutTiers.select(Math.max(1, loan.remainingMinor()));
        long durationSeconds = timeoutTiers.durationSeconds(tier);
        boolean created = database.createLoanTimeoutOnce(loan.id(), playerId, loan.overdueEventId(),
                tier.reason(), loan.remainingMinor(), now, now + durationSeconds * 1000L);
        if (created) {
            database.addLoanNotice(playerId, loan.id(), "TIMEOUT",
                    "You have been timed out for " + (durationSeconds / 60) + " minute(s): " + tier.reason(), now);
        }
    }

    /** Repays up to {@code amountMinor}; releases timeouts once the debt is clear. */
    public long repay(UUID playerId, long amountMinor) throws SQLException {
        long now = System.currentTimeMillis();
        long paid = database.repayLoans(playerId, amountMinor, now);
        if (paid > 0) {
            database.releaseTimeoutsForPaidLoans(playerId, now);
        }
        return paid;
    }

    public void audit(UUID actor, String actorName, String action, String target,
                      String before, String after, String reason) {
        try {
            database.writeAudit(actor, actorName, action, target, before, after, reason,
                    System.currentTimeMillis());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not write audit entry " + action + ": " + exception.getMessage());
        }
    }
}
