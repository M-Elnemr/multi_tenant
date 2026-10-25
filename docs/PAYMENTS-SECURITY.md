# Taking payments: security rules

Written before the first real gateway (Paymob or another) is connected. `app.payments.card-enabled` stays `false` until every line below is true.

**Card data never touches us.** Use the gateway's hosted page, or its mobile SDK sheet. We store only the gateway's payment id, the status and the amount. If a design needs a card number in our form, the design is wrong.

**The server decides the amount.** The charge is created from the order in our database (prices, discount, delivery) at the moment of checkout, never from a number the browser or the app sends. The webhook must match the stored order amount and currency exactly, or the payment is flagged and the order is not marked paid.

**An order becomes paid only from the gateway's signed webhook** (or a server-to-server status check), never from the customer coming back to a "success" page. The return page just asks our server for the order status.

**Webhook rules** (the billing provider code in `com.platform.billing` already follows the first three):
1. Verify the HMAC over the raw body, compared in constant time; reject if the secret is not configured.
2. Store every event by its id before acting (`billing.webhook_events`), and ignore an id seen before: gateways retry.
3. Answer fast, do the work after.
4. Rate limited per IP (`RateLimitFilter`), and the secret is never the development default: `SecretsGuard` stops the application from starting with it.

**Money changes need a second proof.** Refunds, payout details, and changes to payment methods should require a fresh two-step code (`TotpService`) for owners who have it on. Owners and platform admins should turn on two-step sign-in (Settings > Account security, or Admin > Account security) before the first live payment.

**Logs and support.** Never log a full payment payload, card token or webhook secret. Gateway ids are fine.

**Before go-live**: run the gateway's sandbox end to end (success, failure, cancelled, duplicate webhook, webhook before redirect, amount mismatch), take a database backup, and keep the previous release ready to roll back.
