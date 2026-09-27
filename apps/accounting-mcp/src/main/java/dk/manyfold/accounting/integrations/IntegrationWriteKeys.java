package dk.manyfold.accounting.integrations;

import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Idempotency-key validation + canonical request hashing for the write seam (ADR-0043). */
public final class IntegrationWriteKeys {

  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  private IntegrationWriteKeys() {}

  /**
   * Client-supplied keys are logged and persisted -- bound them to a safe charset/length (else
   * 400).
   */
  public static void validateKey(String key) {
    if (key == null || !KEY.matcher(key).matches()) {
      throw new WebApplicationException(
          "invalid Idempotency-Key (1-128 chars of A-Za-z0-9 . _ : -)", 400);
    }
  }

  /**
   * SHA-256 hex of a canonical request string -- binds an idempotency key to its request content.
   */
  public static String hash(String canonical) {
    return hash(canonical.getBytes(StandardCharsets.UTF_8));
  }

  /** SHA-256 hex of canonical request bytes -- used when the write payload is binary, not JSON. */
  public static String hash(byte[] canonical) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical);
      StringBuilder sb = new StringBuilder(64);
      for (byte b : d) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
