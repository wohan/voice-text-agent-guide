package dev.textagent.core;

import java.util.*;

/** Development-only implementation. Production uses the JDBC implementation. */
public final class MemorySessionStore implements SessionStore {
  private final Map<String, SessionState> sessions = new HashMap<>();
  private final Map<String, long[]> budgets = new HashMap<>();

  public synchronized void create(SessionState state, long now, int globalLimit, int ownerLimit) {
    cleanup(now);
    if (sessions.size() >= globalLimit
        || sessions.values().stream().filter(s -> s.owner().equals(state.owner())).count()
            >= ownerLimit) throw new AgentException(429, "Лимит сессий. Попробуйте позже");
    if (sessions.putIfAbsent(state.id(), state) != null)
      throw new AgentException(409, "Сессия уже существует");
  }

  public synchronized SessionState get(String owner, String id, long now) {
    SessionState s = sessions.get(id);
    if (s == null || !s.owner().equals(owner) || s.expiresAt() <= now)
      throw new AgentException(404, "Сессия не найдена");
    return s;
  }

  public synchronized boolean replace(SessionState old, SessionState next) {
    var existing = sessions.get(old.id());
    if (existing == null
        || existing.revision() != old.revision()
        || !existing.owner().equals(old.owner())) return false;
    sessions.put(old.id(), next);
    return true;
  }

  public synchronized void consume(String owner, long now, int limit) {
    long bucket = now / 60000;
    budgets.entrySet().removeIf(e -> e.getValue()[0] < bucket);
    long[] usage = budgets.computeIfAbsent(owner, k -> new long[] {bucket, 0});
    if (++usage[1] > limit)
      throw new AgentException(429, "Слишком много запросов. Подождите минуту");
  }

  public synchronized int cleanup(long now) {
    int before = sessions.size();
    sessions.values().removeIf(s -> s.expiresAt() <= now);
    return before - sessions.size();
  }
}
