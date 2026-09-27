package dk.manyfold.accounting.integrations;

import java.util.Set;

/**
 * The write half of one vendor integration (ADR-0043) -- the sibling of {@link VendorProxy}. Unlike
 * the read proxy, a writer exposes <b>named, typed operations</b> (e.g. {@code
 * createInvoiceDraft}), never a generic {@code write(method, path, body)} -- a generic POST over a
 * financial system is a footgun. Each writer is an {@code @ApplicationScoped} bean doing the
 * host-pinned, credential- injected vendor HTTP; the cross-cutting envelope (role gate,
 * idempotency, audit) lives once in {@link IntegrationWriteExecutor}.
 */
public interface VendorWriter {

  /**
   * The vendor key this writer serves, e.g. {@code "dinero"} -- matches {@link
   * VendorProxy#vendor()}.
   */
  String vendor();

  /** The allowlisted operation ids this writer supports (audit labels + documentation). */
  Set<String> operations();
}
