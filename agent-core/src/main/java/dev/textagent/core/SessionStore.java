package dev.textagent.core;

/** Implementations atomically persist each state transition and its audit event. */
public interface SessionStore {
  void create(SessionState state, long now, int globalLimit, int ownerLimit);

  SessionState get(String owner, String id, long now);

  boolean replace(SessionState expected, SessionState replacement);

  void consume(String owner, long now, int requestsPerMinute);

  int cleanup(long now);
}
