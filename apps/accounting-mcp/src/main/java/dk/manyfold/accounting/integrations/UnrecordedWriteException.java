package dk.manyfold.accounting.integrations;

/**
 * A vendor write that succeeded, but that {@link IntegrationWriteExecutor} could not record as
 * SUCCESS in the ledger (for example a database failure on the commit). The document exists at the
 * vendor. The ledger row stays PENDING, so a same-key retry answers 409 and never calls the vendor
 * a second time; a retry under a NEW key would post a second document.
 *
 * <p>A type of its own, and not a cause, because the cause is the database error and a front door
 * must still recognise the path: the upload resource answers it with the same do-not-retry body as
 * an unknown vendor outcome, where a plain {@link GuardrailException} gets a status-only reply.
 */
public class UnrecordedWriteException extends GuardrailException {

  public UnrecordedWriteException(String message, int status, Throwable cause) {
    super(message, status, cause);
  }
}
