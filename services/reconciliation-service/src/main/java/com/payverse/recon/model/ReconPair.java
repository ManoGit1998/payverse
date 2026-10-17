package com.payverse.recon.model;

import java.math.BigDecimal;

/** Both sides for one reference (a side is null if missing); {@code *InWindow} = inside the business day. */
public record ReconPair(String reference, BigDecimal leftAmount, Boolean leftInWindow, BigDecimal rightAmount, Boolean rightInWindow) {
}
