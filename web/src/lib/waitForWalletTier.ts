import { walletApi } from '../api/endpoints';

/**
 * The wallet's KYC tier is raised asynchronously (user-service → Kafka → wallet-service), a moment after the KYC call
 * returns. Wait briefly for it so the next screen does not show the old tier and zero limits.
 */
export async function waitForWalletTier(previous: string, attempts = 10, delayMs = 500): Promise<void> {
  for (let i = 0; i < attempts; i++) {
    try {
      if ((await walletApi.wallet()).kycTier !== previous) return;
    } catch {
      return; // the screen will load the wallet itself and show any error
    }
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
}
