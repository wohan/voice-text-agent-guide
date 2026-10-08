package dev.textagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Model output is an untrusted proposal, never permission to execute. */
@FunctionalInterface
public interface Planner {
    Proposal plan(List<Operation> operations, JsonNode previousDraft, String message);
    record Proposal(String operationId, JsonNode arguments, String question) {}
}
