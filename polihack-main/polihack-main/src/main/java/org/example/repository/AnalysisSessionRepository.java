package org.example.repository;

import org.example.model.AnalysisSession;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class AnalysisSessionRepository {
    private final ConcurrentMap<String, AnalysisSession> sessions = new ConcurrentHashMap<>();

    public AnalysisSession save(AnalysisSession session) {
        sessions.put(session.getSessionId(), session);
        return session;
    }

    public Optional<AnalysisSession> findBySessionId(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public boolean existsBySessionId(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}
