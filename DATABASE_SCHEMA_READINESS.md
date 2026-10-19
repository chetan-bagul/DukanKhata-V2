# Database Schema Readiness Inventory

## Evidence status

This is an inventory inferred from current JPA entity annotations and Java fields. It is not a database schema dump and does not establish that any non-production or production database matches these mappings. Exact PostgreSQL column names/types, defaults, constraints, indexes, extensions, permissions, and migration history must be checked against an identified sanitized non-production export. No production database was accessed.

The repository contains migrations V1–V3. V1 is a baseline marker, V2 conditionally adds an audit index, and V3 conditionally adds reconciliation/payment columns if the target tables exist. These do not form a complete fresh-install schema. No migration checksums/history from a representative existing database are available.

## Entity-to-table and field inventory (source-inferred)

Unless shown otherwise, entity IDs are UUIDs generated through JPA. Java `String`, `BigDecimal`, `Instant`, `LocalDate`, numeric, boolean, and enum mappings are inferred from the entity declarations; the database representation and naming strategy must be verified. Column overrides and nullability/precision/length are in each referenced entity source file.

| Entity | Expected table | Declared persistent fields (in addition to `id`) |
|---|---|---|
| `User` | `users` | `mobile`, `name`, `passwordHash`, `role`, `active`, `shop`, `staff`, `createdAt` |
| `Shop` | `shops` | `name`, `operatingMode`, `planStatus`, `gstin`, `legalName`, `addressLine`, `city`, `state`, `stateCode`, `pincode`, `nextCustomerNumber`, `createdAt` |
| `Product` | `products` | `shop`, `name`, `sellingPrice`, `averageCostPrice`, `hsnCode`, `gstRate`, `currentStock`, `reorderLevel`, `active`, `createdAt`, `updatedAt` |
| `StockMovement` | `stock_movements` | `shop`, `product`, `type`, `quantity`, `balanceAfter`, `note`, `createdAt` |
| `Customer` | `customers` | `shop`, `customerNumber`, `name`, `mobile`, `address`, `gstin`, `stateCode`, `balance`, `active`, `createdAt`, `updatedAt` |
| `CustomerLedgerEntry` | `customer_ledger_entries` | `shopId`, `customer`, `type`, `paymentMethod`, `amount`, `balanceAfter`, `description`, `referenceType`, `referenceId`, `createdAt` |
| `Supplier` | `suppliers` | `shop`, `name`, `mobile`, `address`, `balancePayable`, `active`, `createdAt`, `updatedAt` |
| `SupplierLedgerEntry` | `supplier_ledger_entries` | `shop`, `supplier`, `type`, `amount`, `balanceAfter`, `reference`, `createdAt` |
| `Staff` | `staff` | `shop`, `name`, `mobile`, `commissionRate`, `active`, `createdAt`, `updatedAt`, `version` |
| `StaffLedgerEntry` | `staff_ledger_entries` | `staff`, `type`, `amount`, `description`, `referenceId`, `createdAt` |
| `Sale` | `pos_sales` | `shop`, `customer`, `seller`, `invoiceNumber`, `subtotal`, `tax`, `taxableAmount`, `cgstAmount`, `sgstAmount`, `igstAmount`, `total`, seller commission fields, `status`, `createdAt`, item/payment collections |
| `SaleItem` | `pos_sale_items` | `sale`, `product`, `itemName`, `unitPrice`, `quantity`, `costPrice`, `lineTotal`, `hsnCode`, `gstRate`, taxable/tax amounts |
| `SalePayment` | `pos_sale_payments` | `sale`, `method`, `amount` |
| `Purchase` | `purchases` | `shop`, `supplier`, purchase/invoice numbers, subtotal/tax/total/paid/payable amounts, `paymentStatus`, `note`, `createdAt`, item collection |
| `PurchaseItem` | `purchase_items` | `purchase`, `product`, `productName`, `quantity`, `unitCost`, `lineTotal` |
| `SaleReturn` | `sale_returns` | `shop`, `sale`, `refundTotal`, `reason`, `status`, `createdAt` |
| `ReturnRefund` | `sale_return_refunds` | `saleReturn`, `method`, `amount` |
| `DailyClosing` | `daily_closings` | `shop`, `businessDate`, `status`, expected/actual sales and collection fields, variance/profit/expense fields, POS reconciliation fields, `notes`, timestamps, item/expense collections |
| `DailyClosingItem` | `daily_closing_items` | `closing`, `product`, product/price snapshots, `quantity`, `amount` |
| `ClosingExpense` | `closing_expenses` | `closing`, `description`, `amount` |
| `FinancialExpense` | `financial_expenses` | `shop`, `expenseDate`, `category`, `description`, `amount`, `recurringMonthly`, `createdAt` |
| `AuditEvent` | `audit_events` | shop/actor identifiers and actor details, resource/action/outcome, entity identifiers, request metadata, `details`, `createdAt` |
| `IdempotencyRecord` | `idempotency_records` | shop/user identifiers, operation/key/hash/response fields, `createdAt` |
| `Notification` | `notification_messages` | `shop`, channel/type/status, recipient/message/provider fields, attempts/error, timestamps |
| `NotificationOutbox` | `notification_outbox` | `eventKey`, `notification`, status/attempt/scheduling/lease/error fields, `createdAt` |
| `SubscriptionPlan` | `subscription_plans` | `code`, `name`, `amount`, `currency`, `durationDays`, `active` |
| `Subscription` | `subscriptions` | `shop`, `plan`, `status`, start/end times, Razorpay order/payment identifiers, `createdAt` |
| `SubscriptionPayment` | `subscription_payments` | `shop`, `plan`, Razorpay identifiers, amount/currency/status, `createdAt` |
| `WebhookEvent` | `webhook_events` | `providerEventId`, `eventType`, `receivedAt` |

## Relationships and declared constraints

The following relationships are inferred from `@ManyToOne`, `@OneToOne`, and collection mappings. `shop_id` and other join-column names are explicit where declared in source; deployed constraints remain unverified.

- Shop-scoped: users, products, customers, suppliers, staff, sales, purchases, stock movements, financial expenses, daily closings, notifications, subscriptions, and payment records reference a shop directly or through their owning parent.
- Sale graph: `pos_sales` references shop and optionally customer/staff; `pos_sale_items` and `pos_sale_payments` reference a sale; sale items optionally reference products. Returns reference shop and sale; refunds reference a return.
- Purchase graph: purchases reference shop and supplier; purchase items reference purchase and product.
- Ledger/inventory: stock movements reference shop and product; supplier ledger references shop/supplier; customer ledger references customer and stores a shop identifier; staff ledger references staff.
- Closing graph: daily closings reference shop; closing items reference closing/product; closing expenses reference closing.
- Subscription/notification: subscriptions and payments reference shop/plan; outbox entries have a one-to-one notification relationship.
- Users reference shop and optionally staff. Audit and idempotency records store identifiers rather than entity relationships.

Named unique constraints declared in entities: `uk_user_mobile(mobile)`, `uk_user_staff(staff_id)`, `uk_customer_shop_number(shop_id,customer_number)`, `uk_idempotency_scope(shop_id,user_id,operation,idempotency_key)`, `uk_pos_sale_shop_invoice(shop_id,invoice_number)`, `uk_sale_return_sale(sale_id)`, `uk_plan_code(code)`, `uk_sub_payment_order(razorpay_order_id)`, `uk_sub_payment_payment(razorpay_payment_id)`, `uk_webhook_event_id(provider_event_id)`, `uk_outbox_event_key(event_key)`, and `uk_closing_shop_date(shop_id,closing_date)`.

Named indexes declared in entities: `idx_supplier_shop_name(shop_id,name)`, `idx_supplier_ledger_supplier_created(supplier_id,created_at)`, `idx_customer_shop_name(shop_id,name)`, `idx_outbox_ready(status,next_attempt_at)`, `idx_outbox_notification(notification_id)`, `idx_notification_shop_created(shop_id,created_at)`, `idx_notification_status(status)`, `idx_staff_ledger_staff_created(staff_id,created_at)`, `idx_sale_return_shop_created(shop_id,created_at)`, `idx_sale_return_sale(sale_id)`, `idx_staff_shop_active(shop_id,active)`, `idx_ledger_customer_created(customer_id,created_at)`, `idx_ledger_shop_created(shop_id,created_at)`, `idx_subscription_shop(shop_id)`, `idx_purchase_item_purchase(purchase_id)`, `idx_purchase_shop_created(shop_id,created_at)`, `idx_purchase_supplier_created(supplier_id,created_at)`, `idx_stock_movement_shop_created(shop_id,created_at)`, `idx_stock_movement_product_created(product_id,created_at)`, `idx_fin_exp_shop_date(shop_id,expense_date)`, `idx_product_shop_active(shop_id,active)`, `idx_audit_shop_time(shop_id,created_at)`, `idx_audit_actor_time(actor_user_id,created_at)`, `idx_audit_entity(entity_type,entity_id)`, `idx_closing_shop_date(shop_id,closing_date)`, `idx_pos_sale_shop_created(shop_id,created_at)`, and `idx_pos_sale_shop_invoice(shop_id,invoice_number)`. These are declarations from source only; database definitions may differ. No complete inventory of database-only objects (including manually created indexes, checks, functions, triggers, views, or extensions) is available. No complete check-constraint inventory is verified.

## Production-profile behavior

`application-prod.yml` sets `spring.jpa.hibernate.ddl-auto=none` and `spring.flyway.enabled=false`; the configuration test loads the YAML resources without starting Spring Boot, creating a datasource, or resolving secrets.

- Compatible existing schema: Hibernate does not mutate or validate it. The `SubscriptionDataInitializer` now seeds a plan only when its code is absent. Existing plans (including inactive plans) retain their operator-managed name, amount, currency, duration, and active status across startup. A unique-code race between simultaneously starting instances is tolerated only when the competing plan becomes visible after the failed insert. This behavior was changed in the supplied candidate, but the test suite could not be rerun in this environment because Maven is unavailable.
- Empty database or missing `subscription_plans`: there is no schema creation. The plan initializer queries `subscription_plans` and fails with a database access error; Spring Boot startup aborts. Isolated H2 tests verify both a generated entity schema for runner behavior and an empty schema with Hibernate DDL disabled for this failure path.
- Missing other tables/columns: Hibernate does not detect them at startup. The plan initializer can succeed if its table is present; the app can then finish startup. The custom readiness endpoint runs only `SELECT 1` and a Redis ping, and the Actuator database health indicator checks connectivity, not application-table completeness. Therefore readiness can be UP while another application table/column is absent; the affected request or scheduled outbox query later fails.
- Incompatible subscription-plan schema: startup fails if the initializer's query or save is incompatible. Other incompatible tables/columns may remain undetected until accessed. `ddl-auto=none` is not schema validation.

Do not enable Flyway or `ddl-auto=validate` until the verified migration path and schema match are demonstrated. No existing database should be used for testing.

## Operator evidence checklist

Request from an authorized operator only:

1. Source identity and written confirmation that the source is a sanitized **non-production** installation representative of the target existing schema.
2. PostgreSQL server major/minor version, installed extensions and versions, and schema/search-path configuration.
3. Complete schema-only export for every application schema, including tables, columns/defaults/nullability, primary/foreign/unique/check constraints, indexes, sequences, views, functions, triggers, and ownership/permissions. Exclude credentials and customer-identifying data.
4. Full `flyway_schema_history` export with installed versions, descriptions, checksums, success states, execution ordering/timestamps, and any failed/ignored entries.
5. Sanitized representative data or an isolated sanitized clone sufficient to test foreign keys, uniqueness, accounting/ledger relationships, subscriptions/payments, and data preservation.
6. Existing Hibernate and application version/profile settings used to create/manage the source schema, with secrets removed.

Do not send passwords, production data, connection URLs containing secrets, or payment credentials.

## Migration verification plan (pending evidence)

1. Compare the complete export/history to all 29 entity mappings and V1–V3 without editing applied migration files.
2. Fresh path: use a new isolated database on the target PostgreSQL major version; apply reviewed migrations in order; repeat validation/migration checks; inspect all objects/constraints/indexes; start the app with schema mutation off; execute representative repository, transaction rollback, uniqueness, and query checks; enable Hibernate validation only as a test assertion after schema parity.
3. Existing path: restore a sanitized copy to a separate isolated database; record row counts and relationship/invariant checks for users, shops, products, inventory, sales, purchases, ledgers, closings, subscriptions, payments, and audit events; test the reviewed adoption/incremental sequence; compare counts, constraints, relationships, and invariants after migration.
4. Verify concurrent stock-out behavior in PostgreSQL using the actual pessimistic product-row lock: with stock 10 and two independent transactions each requesting 7, exactly one succeeds, one reports insufficient stock, final stock is 3, and there is one successful stock movement.
5. Keep production migration disabled. Before any later rollout, require an approved backup, schema-drift review, maintenance/locking plan, post-migration validation, and restore-based rollback plan. No production run is authorized by this checklist.

The current H2 inventory concurrency test is useful unit/integration coverage only; it is not PostgreSQL locking evidence. Docker is unavailable in this environment, and no isolated PostgreSQL test fixture has been verified. Do not use the running local PostgreSQL service because its database identity and contents are unknown.
