package com.loanpilot.orchestrator.policy;

/**
 * Makes sure the vector collection exists before we write to it. An interface so tests can use an in-memory
 * vector store without Qdrant.
 */
@FunctionalInterface
public interface VectorCollection {

    void ensureExists();
}
