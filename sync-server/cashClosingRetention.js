const APPROVAL_RETENTION_TZ = "America/Caracas";

function caracasParts(date = new Date()) {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone: APPROVAL_RETENTION_TZ,
    weekday: "short",
    hour: "numeric",
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  });
  const parts = formatter.formatToParts(date);
  const map = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    weekday: map.weekday,
    hour: Number(map.hour) || 0,
    year: Number(map.year),
    month: Number(map.month),
    day: Number(map.day)
  };
}

/** Lunes 00:00 (America/Caracas) de la semana en curso, en epoch ms UTC. */
function localMidnightUtcMs(year, month, day) {
  for (let hourUtc = 0; hourUtc < 24; hourUtc += 1) {
    const candidate = Date.UTC(year, month - 1, day, hourUtc, 0, 0, 0);
    const parts = caracasParts(new Date(candidate));
    if (parts.year === year && parts.month === month && parts.day === day && parts.hour === 0) {
      return candidate;
    }
  }
  return Date.UTC(year, month - 1, day, 4, 0, 0, 0);
}

function startOfCurrentWeekMillis(date = new Date()) {
  const { year, month, day, weekday } = caracasParts(date);
  const weekdayIndex = {
    Mon: 0,
    Tue: 1,
    Wed: 2,
    Thu: 3,
    Fri: 4,
    Sat: 5,
    Sun: 6
  }[weekday] ?? 0;
  const todayLocalMidnight = localMidnightUtcMs(year, month, day);
  return todayLocalMidnight - weekdayIndex * 24 * 60 * 60 * 1000;
}

function shouldRunWeeklyPurge(date = new Date()) {
  const { weekday, hour } = caracasParts(date);
  return weekday === "Sun" && hour >= 22;
}

function purgeOldCashClosings(state, date = new Date()) {
  const cutoff = startOfCurrentWeekMillis(date);
  const cashClosings = (state.cashClosings || []).filter(
    (closing) => Number(closing.closedAt) >= cutoff
  );
  if (cashClosings.length === (state.cashClosings || []).length) {
    return state;
  }
  return { ...state, cashClosings };
}

function scheduleWeeklyCashClosingPurge(store) {
  if (typeof store.runTransaction !== "function") return;

  let lastPurgeWeekStart = null;

  const maybePurge = async () => {
    if (!shouldRunWeeklyPurge()) return;
    const weekStart = startOfCurrentWeekMillis();
    if (lastPurgeWeekStart === weekStart) return;
    await store.runTransaction(async (state) => ({
      state: purgeOldCashClosings(state),
      result: null
    }));
    lastPurgeWeekStart = weekStart;
    console.log(
      `Cash closing retention: purged records older than week starting ${new Date(weekStart).toISOString()}`
    );
  };

  maybePurge().catch((error) => {
    console.error("Cash closing retention purge failed:", error.message);
  });
  setInterval(() => {
    maybePurge().catch((error) => {
      console.error("Cash closing retention purge failed:", error.message);
    });
  }, 60 * 1000);
}

module.exports = {
  APPROVAL_RETENTION_TZ,
  startOfCurrentWeekMillis,
  shouldRunWeeklyPurge,
  purgeOldCashClosings,
  scheduleWeeklyCashClosingPurge
};
