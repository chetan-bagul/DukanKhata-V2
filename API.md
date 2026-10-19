# DukanKhata Backend API Reference

This reference describes the HTTP endpoints implemented by the Spring Boot backend. Routes use the `/api/v1` prefix. JSON endpoints use `Content-Type: application/json` unless noted otherwise.

## Running the service

The backend listens on `${PORT}` (8080 is exposed by the Docker image). Configure PostgreSQL, Redis, JWT, and subscription/Razorpay environment variables as shown in `.env.example`. `docker-compose.yml` starts PostgreSQL and Redis only; it does not start the backend application. Build and run the backend container with the required environment variables and a database/Redis network connection.

All endpoints except signup/login, public plan listing, Razorpay webhook, and health endpoints require `Authorization: Bearer <accessToken>`. Authentication is stateless. `POST /auth/logout` returns success but does not revoke a JWT; clients should discard it. Roles are `OWNER` and `STAFF`. Unless stated otherwise, a user needs an active trial/subscription to access protected business routes; otherwise the subscription filter returns HTTP 402 `SUBSCRIPTION_REQUIRED`. A shop with no subscription receives the configured trial period.

Successful wrapped responses generally look like `{ "success": true, "data": ..., "message": ... }`; some controllers return DTOs directly and PDF endpoints return binary PDF data. Validation and application errors are handled centrally. Use UUIDs for `{id}` path values and ISO dates (`YYYY-MM-DD`) for date parameters.

Mutating endpoints marked **Idempotency-Key** require that header. Repeating a request with the same key and same request replays the stored result; a key reused for different request content is rejected.

## Authentication and health

| Method | Path | Access | Behavior |
|---|---|---|---|
| POST | `/api/v1/auth/signup` | Public | Creates a shop and its owner account, then returns an access token. Body: `{name,mobile,password,shopName,operatingMode?}`. Name 2–100 chars; Indian mobile format; password 8–72 chars; shop name 2–120 chars. |
| POST | `/api/v1/auth/login` | Public | Authenticates with `{mobile,password}` and returns token/user/shop/plan details. Login attempts are rate limited per configuration. |
| GET | `/api/v1/auth/me` | Authenticated | Returns the current user's identity, role, shop and plan status. |
| POST | `/api/v1/auth/logout` | Authenticated | Returns a success message; JWT remains valid until expiry and is not revoked server-side. |
| GET | `/api/v1/health` | Public | Simple service status (`UP`); does not test dependencies. |
| GET | `/api/v1/live` | Public | Liveness response for process health. |
| GET | `/api/v1/ready` | Public | Executes PostgreSQL `SELECT 1` and Redis `PING`; fails if either dependency is unavailable. |

Auth token response includes `accessToken`, `tokenType`, `userId`, `shopId`, `name`, `mobile`, `role`, `shopName`, `operatingMode`, and `planStatus`.

## Subscription

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/v1/subscription/plans` | Public | Lists available plans (`id,code,name,amount,currency,durationDays`). |
| GET | `/api/v1/subscription/current` | Authenticated | Returns the current shop subscription, if present. |
| POST | `/api/v1/subscription/orders` | Owner | Creates a Razorpay order. Body: `{planCode}`. Returns gateway key/order ID, amount, currency, plan and payment reference. |
| POST | `/api/v1/subscription/verify` | Owner | Verifies a completed payment. Body: `{razorpayOrderId,razorpayPaymentId,razorpaySignature}`. |
| POST | `/api/v1/subscription/webhook` | Public, signature checked | Receives Razorpay webhook JSON; supply `X-Razorpay-Signature`. |

## Products and inventory

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/v1/products` | Authenticated | Lists active products with prices, stock, reorder threshold and GST details. |
| POST | `/api/v1/products` | Owner | Creates product. Body: `{name,sellingPrice,averageCostPrice?,reorderLevel?,hsnCode?,gstRate?}`. Returns HTTP 201. |
| PUT | `/api/v1/products/{id}` | Owner | Replaces editable product fields using the same request shape. |
| DELETE | `/api/v1/products/{id}` | Owner | Deactivates product; does not physically delete it. |
| POST | `/api/v1/inventory/{productId}/receive` | Owner | Receives stock. Body: `{quantity,note?}`; quantity must be positive. Returns updated stock. |
| POST | `/api/v1/inventory/{productId}/adjust` | Owner | Adjusts stock. Body: `{quantity,direction,note?}` where direction is `IN` or `OUT`. |
| GET | `/api/v1/inventory/low-stock` | Authenticated | Lists products at or below reorder level. |
| GET | `/api/v1/inventory/{productId}/history` | Authenticated | Lists stock movements with type, quantity, resulting balance and note. |

## Point of sale and invoices

| Method | Path | Access | Behavior |
|---|---|---|---|
| POST | `/api/v1/pos/sales` | Owner or Staff | Creates a sale; requires **Idempotency-Key**. Body: `{customerId?,sellerId?,items,payments}`. Each item: `{productId?,itemName,unitPrice,quantity,hsnCode?,gstRate?}`. Each payment: `{method,amount}`. Returns HTTP 201 with invoice, tax totals, sale items and payments. |
| GET | `/api/v1/pos/sales` | Owner or Staff | Paged sales list. Query: `page` (default 0), `size` (default 25, clamped 1–100). `X-Page`, `X-Page-Size`, `X-Has-More` response headers describe pagination. |
| GET | `/api/v1/pos/sales/{id}` | Owner or Staff | Loads one sale in the current shop. |
| GET | `/api/v1/invoices/{id}/pdf` | Authenticated | Downloads a sale invoice as `application/pdf`, scoped to current shop. |

Sale item name, unit price, and quantity are required; at least one item and one payment are required. Payment method values follow the `PaymentMethod` enum in the code. The sale service computes totals and applicable GST and records stock/customer/seller effects according to the sale rules.

## Customers and customer ledger

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/v1/customers` | Authenticated | Lists customers. |
| GET | `/api/v1/customers/{id}` | Authenticated | Returns a customer and balance. |
| POST | `/api/v1/customers` | Owner | Creates a customer. Body: `{name,mobile?,address?,gstin?,stateCode?}`. |
| PUT | `/api/v1/customers/{id}` | Owner | Updates customer fields using the same shape. |
| DELETE | `/api/v1/customers/{id}` | Owner | Deactivates customer. |
| GET | `/api/v1/customers/{id}/ledger` | Authenticated | Lists ledger entries and balance snapshots. |
| POST | `/api/v1/customers/{id}/ledger/charge` | Authenticated | Adds a customer charge (udhar); requires **Idempotency-Key**. Body: `{amount,description?}`. |
| POST | `/api/v1/customers/{id}/ledger/payment` | Authenticated | Records a customer repayment; requires **Idempotency-Key**. Body: `{amount,paymentMethod,description?}`; `paymentMethod` is `CASH` or `UPI`. |

Ledger amounts must be positive and have at most two decimal places. Customer name is required; mobile, GSTIN, and state code are validated when supplied.

## Suppliers and purchases

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/v1/suppliers` | Authenticated | Lists active suppliers and payable balance. |
| POST | `/api/v1/suppliers` | Authenticated | Creates supplier. Body: `{name,mobile?,address?}`. |
| PUT | `/api/v1/suppliers/{id}` | Authenticated | Updates supplier details. |
| DELETE | `/api/v1/suppliers/{id}` | Authenticated | Deactivates supplier; returns 204. |
| GET | `/api/v1/suppliers/{id}/ledger` | Authenticated | Lists supplier ledger entries. |
| POST | `/api/v1/suppliers/{id}/payments` | Authenticated | Records payment to supplier; requires **Idempotency-Key**. Body: `{amount,reference?}`. |
| GET | `/api/v1/purchases` | Owner | Lists purchases. |
| GET | `/api/v1/purchases/{id}` | Owner | Gets one purchase. |
| POST | `/api/v1/purchases` | Owner | Creates purchase; requires **Idempotency-Key**. Body: `{supplierId,supplierInvoiceNumber?,items,paidAmount,note?}`. Items: `{productId,quantity,unitCost}`. Creates stock and supplier payable effects and returns purchase totals/payment status. |

## Staff and user accounts

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/v1/staff` | Owner | Lists staff and payable amounts. |
| POST | `/api/v1/staff` | Owner | Creates staff. Body: `{name,mobile?,commissionRate,active?}`. Returns HTTP 201. |
| PUT | `/api/v1/staff/{id}` | Owner | Updates staff details. |
| DELETE | `/api/v1/staff/{id}` | Owner | Deletes/deactivates staff as implemented by service; returns 204. |
| GET | `/api/v1/staff/{id}/ledger` | Owner | Lists commission/payout/adjustment ledger. |
| POST | `/api/v1/staff/{id}/payouts` | Owner | Records payout; requires **Idempotency-Key**. Body: `{amount,description}`. |
| POST | `/api/v1/staff/{id}/adjustments` | Owner | Adds signed balance adjustment. Body: `{amount,description}`. |
| GET | `/api/v1/users` | Owner | Lists login accounts for shop. |
| POST | `/api/v1/users/staff` | Owner | Creates login account for existing staff. Body: `{staffId,mobile,password}`. |
| PATCH | `/api/v1/users/{id}/active?value=true` | Owner | Activates or deactivates account. Query `value` is required boolean. |

## Daily closing, finance and reports

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/v1/closings` | Owner | Lists daily closings. |
| GET | `/api/v1/closings/{id}` | Owner | Returns closing, item/expense breakdown, customer repayment totals, and POS totals by payment method. |
| POST | `/api/v1/closings` | Owner | Creates or saves a closing. Body: `{businessDate,items,cash,upi,udhar,expenses?,notes?}`. Items: `{productId,quantity}`; expenses: `{description,amount}`. |
| POST | `/api/v1/closings/{id}/close` | Owner | Finalizes closing. Actual collection is manual cash + UPI + Udhar + cash/UPI customer repayments; variance is expected collection minus actual collection. |
| POST | `/api/v1/closings/{id}/sync-pos` | Owner | Stores POS sale/payment totals separately and compares POS expected sales with manual collections without overwriting manual cash, UPI, or Udhar. |
| POST | `/api/v1/finance/expenses` | Owner | Adds expense. Body: `{expenseDate,category,description,amount,recurringMonthly}`. |
| GET | `/api/v1/finance/expenses?from=YYYY-MM-DD&to=YYYY-MM-DD` | Owner | Lists expenses in date range. |
| DELETE | `/api/v1/finance/expenses/{id}` | Owner | Deletes expense; returns 204. |
| GET | `/api/v1/finance/summary?from=YYYY-MM-DD&to=YYYY-MM-DD` | Owner | Returns sales count, revenue, costs, margins, expense categories and net profit for range. |
| GET | `/api/v1/reports/summary?from=YYYY-MM-DD&to=YYYY-MM-DD` | Owner | Returns daily closing rows and aggregate closing summary for range. |
| GET | `/api/v1/reports/pdf?from=YYYY-MM-DD&to=YYYY-MM-DD` | Owner | Downloads PDF report for date range. |

Finance expense category values are the `ExpenseCategory` enum. Date ranges use inclusive-looking service filters; ensure `from` is not after `to`.

## Returns

| Method | Path | Access | Behavior |
|---|---|---|---|
| POST | `/api/v1/returns/sales/{saleId}` | Owner | Creates a return/refund; requires **Idempotency-Key**. Body: `{reason,refunds}` where each refund is `{method,amount}`. Returns HTTP 201. |
| GET | `/api/v1/returns` | Owner | Lists returns for the current shop. |

## Shop settings, notifications and audit

| Method | Path | Access | Behavior |
|---|---|---|---|
| GET | `/api/v1/shop/settings` | Owner | Reads current shop settings (cached for up to five minutes). |
| PUT | `/api/v1/shop/settings` | Owner | Updates settings. Body: `{name,gstin?,legalName?,addressLine?,city?,state?,stateCode?,pincode?}`. |
| GET | `/api/v1/notifications` | Authenticated | Lists latest notifications. |
| POST | `/api/v1/notifications` | Owner | Sends/queues notification. Body: `{channel,type,recipient,subject,message}`. |
| POST | `/api/v1/notifications/{id}/retry` | Owner | Retries sending a notification. |
| GET | `/api/v1/audit?limit=100` | Owner | Lists latest audit events; default limit is 100. |

## Common implementation notes

- JSON DTO fields and enum values are case-sensitive; enum strings are uppercase Java enum names unless the application maps them otherwise.
- Most business data is scoped to the authenticated user's shop. The service layer performs resource existence and business-rule checks.
- CORS allows configured localhost ports, Vercel subdomains, and DukanKhata domains. Allowed request headers are `Authorization`, `Content-Type`, and `Idempotency-Key`; response pagination headers are exposed to browsers.
- `GET /actuator/health` is also permitted by security and exposed by Spring Actuator.
- Docker builds the executable Spring Boot jar using Java 21/Maven and exposes port 8080. `docker-compose.yml` provisions PostgreSQL 17 and Redis 7; application configuration must be provided separately.
