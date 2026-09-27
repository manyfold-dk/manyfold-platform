package dk.manyfold.accounting.integrations.model;

/** Lifecycle of one integration write attempt (ADR-0043). */
public enum IntegrationWriteStatus {
  PENDING,
  SUCCESS,
  FAILED
}
