DO $migration$
DECLARE
    v_application_tables integer;
    v_application_columns integer;
    v_constraints integer;
    v_indexes integer;
    v_uuid_defaults integer;
    v_schema_fingerprint text;
    v_index_definition text;
BEGIN
    SELECT count(*) INTO v_application_tables
    FROM pg_tables
    WHERE schemaname = 'public'
      AND tablename <> 'flyway_schema_history';

    IF v_application_tables = 0 THEN
CREATE TABLE public.audit_events (
    id uuid NOT NULL,
    action character varying(100) NOT NULL,
    actor_mobile character varying(20),
    actor_name character varying(100),
    actor_user_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    details character varying(1000),
    entity_id character varying(80),
    entity_type character varying(80),
    http_method character varying(80),
    ip_address character varying(80),
    outcome character varying(20) NOT NULL,
    request_path character varying(300),
    resource character varying(60) NOT NULL,
    shop_id uuid,
    user_agent character varying(300)
);

CREATE TABLE public.closing_expenses (
    id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    description character varying(160) NOT NULL,
    closing_id uuid NOT NULL
);

CREATE TABLE public.customer_ledger_entries (
    id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    balance_after numeric(14,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    description character varying(300),
    payment_method character varying(20),
    reference_id uuid,
    reference_type character varying(40),
    shop_id uuid NOT NULL,
    type character varying(20) NOT NULL,
    customer_id uuid NOT NULL,
    CONSTRAINT customer_ledger_entries_payment_method_check CHECK (((payment_method)::text = ANY (ARRAY[('CASH'::character varying)::text, ('UPI'::character varying)::text, ('UDHAR'::character varying)::text]))),
    CONSTRAINT customer_ledger_entries_type_check CHECK (((type)::text = ANY (ARRAY[('CHARGE'::character varying)::text, ('PAYMENT'::character varying)::text, ('ADJUSTMENT'::character varying)::text])))
);

CREATE TABLE public.customers (
    id uuid NOT NULL,
    active boolean NOT NULL,
    address character varying(300),
    balance numeric(14,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    customer_number integer NOT NULL,
    gstin character varying(15),
    mobile character varying(15),
    name character varying(120) NOT NULL,
    state_code character varying(2),
    updated_at timestamp(6) with time zone NOT NULL,
    shop_id uuid NOT NULL
);

CREATE TABLE public.daily_closing_items (
    id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    product_name_snapshot character varying(120) NOT NULL,
    quantity numeric(12,3) NOT NULL,
    unit_price_snapshot numeric(12,2) NOT NULL,
    closing_id uuid NOT NULL,
    product_id uuid NOT NULL
);

CREATE TABLE public.daily_closings (
    id uuid NOT NULL,
    actual_collection numeric(14,2) NOT NULL,
    closing_date date NOT NULL,
    cash numeric(14,2) NOT NULL,
    closed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    customer_repayment_cash numeric(14,2) NOT NULL,
    customer_repayment_upi numeric(14,2) NOT NULL,
    expected_sales numeric(14,2) NOT NULL,
    expenses_total numeric(14,2) NOT NULL,
    net_profit numeric(14,2) NOT NULL,
    notes character varying(500),
    pos_auto_aggregated boolean NOT NULL,
    pos_cash numeric(14,2) NOT NULL,
    pos_sale_count bigint NOT NULL,
    pos_sales_total numeric(14,2) NOT NULL,
    pos_udhar numeric(14,2) NOT NULL,
    pos_upi numeric(14,2) NOT NULL,
    status character varying(20) NOT NULL,
    udhar numeric(14,2) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    upi numeric(14,2) NOT NULL,
    variance numeric(14,2) NOT NULL,
    shop_id uuid NOT NULL,
    CONSTRAINT daily_closings_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('CLOSED'::character varying)::text])))
);

CREATE TABLE public.financial_expenses (
    id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    category character varying(30) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    description character varying(180) NOT NULL,
    expense_date date NOT NULL,
    recurring_monthly boolean NOT NULL,
    shop_id uuid NOT NULL,
    CONSTRAINT financial_expenses_category_check CHECK (((category)::text = ANY (ARRAY[('RENT'::character varying)::text, ('ELECTRICITY'::character varying)::text, ('GAS'::character varying)::text, ('STAFF_SALARY'::character varying)::text, ('TRANSPORT'::character varying)::text, ('MAINTENANCE'::character varying)::text, ('MARKETING'::character varying)::text, ('OTHER'::character varying)::text])))
);

CREATE TABLE public.idempotency_records (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    operation character varying(80) NOT NULL,
    request_hash character varying(64) NOT NULL,
    response_json text NOT NULL,
    shop_id uuid NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE public.notification_messages (
    id uuid NOT NULL,
    attempts integer NOT NULL,
    channel character varying(30) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    last_error character varying(500),
    message text NOT NULL,
    provider character varying(30) NOT NULL,
    provider_message_id character varying(120),
    recipient character varying(40) NOT NULL,
    sent_at timestamp(6) with time zone,
    status character varying(20) NOT NULL,
    subject character varying(200) NOT NULL,
    type character varying(40) NOT NULL,
    shop_id uuid NOT NULL,
    CONSTRAINT notification_messages_channel_check CHECK (((channel)::text = ANY (ARRAY[('WHATSAPP'::character varying)::text, ('SMS'::character varying)::text]))),
    CONSTRAINT notification_messages_status_check CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('PROCESSING'::character varying)::text, ('SENT'::character varying)::text, ('FAILED'::character varying)::text, ('DEAD_LETTER'::character varying)::text]))),
    CONSTRAINT notification_messages_type_check CHECK (((type)::text = ANY (ARRAY[('INVOICE'::character varying)::text, ('PAYMENT_RECEIPT'::character varying)::text, ('UDHAR_REMINDER'::character varying)::text, ('LOW_STOCK'::character varying)::text, ('SUPPLIER_PAYMENT'::character varying)::text, ('SUBSCRIPTION'::character varying)::text])))
);

CREATE TABLE public.notification_outbox (
    id uuid NOT NULL,
    attempts integer NOT NULL,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    event_key character varying(160) NOT NULL,
    last_error character varying(500),
    locked_at timestamp(6) with time zone,
    next_attempt_at timestamp(6) with time zone NOT NULL,
    status character varying(20) NOT NULL,
    notification_id uuid NOT NULL,
    CONSTRAINT notification_outbox_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text, ('COMPLETED'::character varying)::text, ('DEAD_LETTER'::character varying)::text])))
);

CREATE TABLE public.pos_sale_items (
    id uuid NOT NULL,
    cgst_amount numeric(14,2) NOT NULL,
    cost_price numeric(12,2) DEFAULT 0 NOT NULL,
    gst_rate numeric(5,2) NOT NULL,
    hsn_code character varying(8),
    igst_amount numeric(14,2) NOT NULL,
    item_name character varying(160) NOT NULL,
    line_total numeric(14,2) NOT NULL,
    quantity numeric(12,3) NOT NULL,
    sgst_amount numeric(14,2) NOT NULL,
    taxable_amount numeric(14,2) NOT NULL,
    unit_price numeric(12,2) NOT NULL,
    product_id uuid,
    sale_id uuid NOT NULL
);

CREATE TABLE public.pos_sale_payments (
    id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    method character varying(20) NOT NULL,
    sale_id uuid NOT NULL,
    CONSTRAINT pos_sale_payments_method_check CHECK (((method)::text = ANY (ARRAY[('CASH'::character varying)::text, ('UPI'::character varying)::text, ('UDHAR'::character varying)::text])))
);

CREATE TABLE public.pos_sales (
    id uuid NOT NULL,
    cgst_amount numeric(14,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    igst_amount numeric(14,2) NOT NULL,
    invoice_number character varying(40) NOT NULL,
    seller_commission_amount numeric(14,2) NOT NULL,
    seller_commission_rate numeric(5,2) NOT NULL,
    sgst_amount numeric(14,2) NOT NULL,
    status character varying(20) NOT NULL,
    subtotal numeric(14,2) NOT NULL,
    tax numeric(14,2) NOT NULL,
    taxable_amount numeric(14,2) NOT NULL,
    total numeric(14,2) NOT NULL,
    customer_id uuid,
    seller_id uuid,
    shop_id uuid NOT NULL,
    CONSTRAINT pos_sales_status_check CHECK (((status)::text = ANY (ARRAY[('COMPLETED'::character varying)::text, ('VOIDED'::character varying)::text, ('RETURNED'::character varying)::text])))
);

CREATE TABLE public.products (
    id uuid NOT NULL,
    active boolean NOT NULL,
    average_cost_price numeric(12,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    current_stock numeric(12,3) NOT NULL,
    gst_rate numeric(5,2) NOT NULL,
    hsn_code character varying(8),
    name character varying(120) NOT NULL,
    reorder_level numeric(12,3) NOT NULL,
    selling_price numeric(12,2) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    shop_id uuid NOT NULL
);

CREATE TABLE public.purchase_items (
    id uuid NOT NULL,
    line_total numeric(14,2) NOT NULL,
    product_name character varying(120) NOT NULL,
    quantity numeric(12,3) NOT NULL,
    unit_cost numeric(12,2) NOT NULL,
    product_id uuid NOT NULL,
    purchase_id uuid NOT NULL
);

CREATE TABLE public.purchases (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    note character varying(500),
    paid_amount numeric(14,2) NOT NULL,
    payable_amount numeric(14,2) NOT NULL,
    payment_status character varying(20) NOT NULL,
    purchase_number character varying(50) NOT NULL,
    subtotal numeric(14,2) NOT NULL,
    supplier_invoice_number character varying(80),
    tax numeric(14,2) NOT NULL,
    total numeric(14,2) NOT NULL,
    shop_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    CONSTRAINT purchases_payment_status_check CHECK (((payment_status)::text = ANY (ARRAY[('PAID'::character varying)::text, ('PARTIAL'::character varying)::text, ('CREDIT'::character varying)::text])))
);

CREATE TABLE public.sale_return_refunds (
    id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    method character varying(20) NOT NULL,
    return_id uuid NOT NULL,
    CONSTRAINT sale_return_refunds_method_check CHECK (((method)::text = ANY (ARRAY[('CASH'::character varying)::text, ('UPI'::character varying)::text, ('UDHAR'::character varying)::text])))
);

CREATE TABLE public.sale_returns (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    reason character varying(500) NOT NULL,
    refund_total numeric(14,2) NOT NULL,
    status character varying(20) NOT NULL,
    sale_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    CONSTRAINT sale_returns_status_check CHECK (((status)::text = 'COMPLETED'::text))
);

CREATE TABLE public.shops (
    id uuid NOT NULL,
    address_line character varying(300),
    city character varying(80),
    created_at timestamp(6) with time zone NOT NULL,
    gstin character varying(15),
    legal_name character varying(160),
    name character varying(120) NOT NULL,
    next_customer_number integer NOT NULL,
    operating_mode character varying(20) NOT NULL,
    pincode character varying(6),
    plan_status character varying(30) NOT NULL,
    state character varying(80),
    state_code character varying(2)
);

CREATE TABLE public.staff (
    id uuid NOT NULL,
    active boolean NOT NULL,
    commission_rate numeric(5,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    mobile character varying(15),
    name character varying(100) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    shop_id uuid NOT NULL
);

CREATE TABLE public.staff_ledger_entries (
    id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    description character varying(500) NOT NULL,
    reference_id uuid,
    type character varying(30) NOT NULL,
    staff_id uuid NOT NULL,
    CONSTRAINT staff_ledger_entries_type_check CHECK (((type)::text = ANY (ARRAY[('COMMISSION_EARNED'::character varying)::text, ('PAYOUT'::character varying)::text, ('ADJUSTMENT'::character varying)::text])))
);

CREATE TABLE public.stock_movements (
    id uuid NOT NULL,
    balance_after numeric(12,3) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    note character varying(300),
    quantity numeric(12,3) NOT NULL,
    type character varying(20) NOT NULL,
    product_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    CONSTRAINT stock_movements_type_check CHECK (((type)::text = ANY (ARRAY[('OPENING'::character varying)::text, ('PURCHASE'::character varying)::text, ('SALE'::character varying)::text, ('ADJUSTMENT_IN'::character varying)::text, ('ADJUSTMENT_OUT'::character varying)::text, ('RETURN_IN'::character varying)::text])))
);

CREATE TABLE public.subscription_payments (
    id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    currency character varying(3) NOT NULL,
    razorpay_order_id character varying(80) NOT NULL,
    razorpay_payment_id character varying(80),
    status character varying(20) NOT NULL,
    plan_id uuid NOT NULL,
    shop_id uuid NOT NULL
);

CREATE TABLE public.subscription_plans (
    id uuid NOT NULL,
    active boolean NOT NULL,
    amount numeric(12,2) NOT NULL,
    code character varying(40) NOT NULL,
    currency character varying(3) NOT NULL,
    duration_days integer NOT NULL,
    name character varying(100) NOT NULL
);

CREATE TABLE public.subscriptions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    ends_at timestamp(6) with time zone,
    razorpay_order_id character varying(80),
    razorpay_payment_id character varying(80),
    starts_at timestamp(6) with time zone,
    status character varying(30) NOT NULL,
    plan_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    CONSTRAINT subscriptions_status_check CHECK (((status)::text = ANY (ARRAY[('TRIAL'::character varying)::text, ('PAYMENT_PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('EXPIRED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

CREATE TABLE public.supplier_ledger_entries (
    id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    balance_after numeric(14,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    reference character varying(300),
    type character varying(20) NOT NULL,
    shop_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    CONSTRAINT supplier_ledger_entries_type_check CHECK (((type)::text = ANY (ARRAY[('CHARGE'::character varying)::text, ('PAYMENT'::character varying)::text])))
);

CREATE TABLE public.suppliers (
    id uuid NOT NULL,
    active boolean NOT NULL,
    address character varying(200),
    balance_payable numeric(14,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    mobile character varying(20),
    name character varying(140) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    shop_id uuid NOT NULL
);

CREATE TABLE public.users (
    id uuid NOT NULL,
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    mobile character varying(10) NOT NULL,
    name character varying(100) NOT NULL,
    password_hash character varying(100) NOT NULL,
    role character varying(30) NOT NULL,
    shop_id uuid NOT NULL,
    staff_id uuid
);

CREATE TABLE public.webhook_events (
    id uuid NOT NULL,
    event_type character varying(60) NOT NULL,
    provider_event_id character varying(120) NOT NULL,
    received_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.audit_events
    ADD CONSTRAINT audit_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.closing_expenses
    ADD CONSTRAINT closing_expenses_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.customer_ledger_entries
    ADD CONSTRAINT customer_ledger_entries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.daily_closing_items
    ADD CONSTRAINT daily_closing_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.daily_closings
    ADD CONSTRAINT daily_closings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.financial_expenses
    ADD CONSTRAINT financial_expenses_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notification_messages
    ADD CONSTRAINT notification_messages_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notification_outbox
    ADD CONSTRAINT notification_outbox_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pos_sale_items
    ADD CONSTRAINT pos_sale_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pos_sale_payments
    ADD CONSTRAINT pos_sale_payments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pos_sales
    ADD CONSTRAINT pos_sales_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.purchase_items
    ADD CONSTRAINT purchase_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.purchases
    ADD CONSTRAINT purchases_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.sale_return_refunds
    ADD CONSTRAINT sale_return_refunds_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.sale_returns
    ADD CONSTRAINT sale_returns_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.shops
    ADD CONSTRAINT shops_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.staff_ledger_entries
    ADD CONSTRAINT staff_ledger_entries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.staff
    ADD CONSTRAINT staff_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT stock_movements_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.subscription_payments
    ADD CONSTRAINT subscription_payments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.subscription_plans
    ADD CONSTRAINT subscription_plans_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.subscriptions
    ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.supplier_ledger_entries
    ADD CONSTRAINT supplier_ledger_entries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.suppliers
    ADD CONSTRAINT suppliers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.daily_closings
    ADD CONSTRAINT uk_closing_shop_date UNIQUE (shop_id, closing_date);

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT uk_customer_shop_number UNIQUE (shop_id, customer_number);

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT uk_idempotency_scope UNIQUE (shop_id, user_id, operation, idempotency_key);

ALTER TABLE ONLY public.notification_outbox
    ADD CONSTRAINT uk_outbox_event_key UNIQUE (event_key);

ALTER TABLE ONLY public.subscription_plans
    ADD CONSTRAINT uk_plan_code UNIQUE (code);

ALTER TABLE ONLY public.pos_sales
    ADD CONSTRAINT uk_pos_sale_shop_invoice UNIQUE (shop_id, invoice_number);

ALTER TABLE ONLY public.sale_returns
    ADD CONSTRAINT uk_sale_return_sale UNIQUE (sale_id);

ALTER TABLE ONLY public.subscription_payments
    ADD CONSTRAINT uk_sub_payment_order UNIQUE (razorpay_order_id);

ALTER TABLE ONLY public.subscription_payments
    ADD CONSTRAINT uk_sub_payment_payment UNIQUE (razorpay_payment_id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_user_mobile UNIQUE (mobile);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_user_staff UNIQUE (staff_id);

ALTER TABLE ONLY public.webhook_events
    ADD CONSTRAINT uk_webhook_event_id UNIQUE (provider_event_id);

ALTER TABLE ONLY public.notification_outbox
    ADD CONSTRAINT ukrxv4x9y12lh84ky8x2b27ostb UNIQUE (notification_id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.webhook_events
    ADD CONSTRAINT webhook_events_pkey PRIMARY KEY (id);

CREATE INDEX idx_audit_actor_time ON public.audit_events USING btree (actor_user_id, created_at);

CREATE INDEX idx_audit_entity ON public.audit_events USING btree (entity_type, entity_id);

CREATE INDEX idx_audit_shop_time ON public.audit_events USING btree (shop_id, created_at);

CREATE INDEX idx_closing_shop_date ON public.daily_closings USING btree (shop_id, closing_date);

CREATE INDEX idx_customer_shop_name ON public.customers USING btree (shop_id, name);

CREATE INDEX idx_fin_exp_shop_date ON public.financial_expenses USING btree (shop_id, expense_date);

CREATE INDEX idx_ledger_customer_created ON public.customer_ledger_entries USING btree (customer_id, created_at);

CREATE INDEX idx_ledger_shop_created ON public.customer_ledger_entries USING btree (shop_id, created_at);

CREATE INDEX idx_notification_shop_created ON public.notification_messages USING btree (shop_id, created_at);

CREATE INDEX idx_notification_status ON public.notification_messages USING btree (status);

CREATE INDEX idx_outbox_notification ON public.notification_outbox USING btree (notification_id);

CREATE INDEX idx_outbox_ready ON public.notification_outbox USING btree (status, next_attempt_at);

CREATE INDEX idx_pos_sale_shop_created ON public.pos_sales USING btree (shop_id, created_at);

CREATE INDEX idx_pos_sale_shop_invoice ON public.pos_sales USING btree (shop_id, invoice_number);

CREATE INDEX idx_product_shop_active ON public.products USING btree (shop_id, active);

CREATE INDEX idx_purchase_item_purchase ON public.purchase_items USING btree (purchase_id);

CREATE INDEX idx_purchase_shop_created ON public.purchases USING btree (shop_id, created_at);

CREATE INDEX idx_purchase_supplier_created ON public.purchases USING btree (supplier_id, created_at);

CREATE INDEX idx_sale_return_sale ON public.sale_returns USING btree (sale_id);

CREATE INDEX idx_sale_return_shop_created ON public.sale_returns USING btree (shop_id, created_at);

CREATE INDEX idx_staff_ledger_staff_created ON public.staff_ledger_entries USING btree (staff_id, created_at);

CREATE INDEX idx_staff_shop_active ON public.staff USING btree (shop_id, active);

CREATE INDEX idx_stock_movement_product_created ON public.stock_movements USING btree (product_id, created_at);

CREATE INDEX idx_stock_movement_shop_created ON public.stock_movements USING btree (shop_id, created_at);

CREATE INDEX idx_subscription_shop ON public.subscriptions USING btree (shop_id);

CREATE INDEX idx_supplier_ledger_supplier_created ON public.supplier_ledger_entries USING btree (supplier_id, created_at);

CREATE INDEX idx_supplier_shop_name ON public.suppliers USING btree (shop_id, name);

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT fk2u2391jbbtdnpnrywyfwxwnje FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.pos_sales
    ADD CONSTRAINT fk4f3dwi1fqwuldkblycd5gdgl5 FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.subscriptions
    ADD CONSTRAINT fk58jryv6sn2usah2hl7apn72gn FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.suppliers
    ADD CONSTRAINT fk5dxvi9w5linnkj9c5a405cmy9 FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.supplier_ledger_entries
    ADD CONSTRAINT fk6rybfpuckshf0b4g3jjbcwivy FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);

ALTER TABLE ONLY public.products
    ADD CONSTRAINT fk7kp8sbhxboponhx3lxqtmkcoj FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.financial_expenses
    ADD CONSTRAINT fk926m8rekf2wx2x7qruj9t4f0i FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.purchases
    ADD CONSTRAINT fk9ho3w23v5du4x0hrp6rqs1wmh FOREIGN KEY (supplier_id) REFERENCES public.suppliers(id);

ALTER TABLE ONLY public.purchases
    ADD CONSTRAINT fkan7atuaei7kbvf5c59ukho5ru FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.closing_expenses
    ADD CONSTRAINT fkaspks1ori3tvwg5ms12qjqlyx FOREIGN KEY (closing_id) REFERENCES public.daily_closings(id);

ALTER TABLE ONLY public.pos_sales
    ADD CONSTRAINT fkb9q9b7i6d8te7eggtualdejfa FOREIGN KEY (seller_id) REFERENCES public.staff(id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fkbadc51v95q4hmcfflt8g3yfck FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fkbglg01rrxvu2s48rjyopyexkj FOREIGN KEY (staff_id) REFERENCES public.staff(id);

ALTER TABLE ONLY public.pos_sale_items
    ADD CONSTRAINT fkbsuiqg9jubiqfa04urdksp091 FOREIGN KEY (product_id) REFERENCES public.products(id);

ALTER TABLE ONLY public.purchase_items
    ADD CONSTRAINT fkbwtjp8gfcre77l1mxverb6up0 FOREIGN KEY (product_id) REFERENCES public.products(id);

ALTER TABLE ONLY public.notification_messages
    ADD CONSTRAINT fkc95hrtemqq58h29g1crv62ueh FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT fkeuirokymfgeui0djxs98xcrjo FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.customer_ledger_entries
    ADD CONSTRAINT fkf1salmk445vu0n1gd9c8ka7ry FOREIGN KEY (customer_id) REFERENCES public.customers(id);

ALTER TABLE ONLY public.subscriptions
    ADD CONSTRAINT fkg41f5iev0mretaqhvepf0lks0 FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id);

ALTER TABLE ONLY public.staff
    ADD CONSTRAINT fkgpmqw5g8ike1tnstc23o0r7a5 FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.sale_returns
    ADD CONSTRAINT fkh9v46xqhdyxtxeew1etutkciu FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.purchase_items
    ADD CONSTRAINT fkhcski0jcuja0o3vhb7o15yqvi FOREIGN KEY (purchase_id) REFERENCES public.purchases(id);

ALTER TABLE ONLY public.pos_sales
    ADD CONSTRAINT fki8h7ig230n8ec46xvq9sa6uyj FOREIGN KEY (customer_id) REFERENCES public.customers(id);

ALTER TABLE ONLY public.subscription_payments
    ADD CONSTRAINT fkifbl910fi3d6x90qq1dje8oht FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id);

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT fkjcaag8ogfjxpwmqypi1wfdaog FOREIGN KEY (product_id) REFERENCES public.products(id);

ALTER TABLE ONLY public.notification_outbox
    ADD CONSTRAINT fkjcmmmhxvw4g1vdk1wa2ggm5rf FOREIGN KEY (notification_id) REFERENCES public.notification_messages(id);

ALTER TABLE ONLY public.subscription_payments
    ADD CONSTRAINT fklxahejtb43aurgpvcqi74g9ar FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.daily_closing_items
    ADD CONSTRAINT fkn0xmulm2h86shdsrcf310efrf FOREIGN KEY (closing_id) REFERENCES public.daily_closings(id);

ALTER TABLE ONLY public.pos_sale_payments
    ADD CONSTRAINT fknllp42hh85vfbkpjxegpuod0p FOREIGN KEY (sale_id) REFERENCES public.pos_sales(id);

ALTER TABLE ONLY public.daily_closings
    ADD CONSTRAINT fkp3vcy0q0ewibnljufpuxgb4kl FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.supplier_ledger_entries
    ADD CONSTRAINT fkp568mxhlqk5xcp0owkootr05m FOREIGN KEY (shop_id) REFERENCES public.shops(id);

ALTER TABLE ONLY public.daily_closing_items
    ADD CONSTRAINT fkp9jnej4ey487m6k00mvgg5e33 FOREIGN KEY (product_id) REFERENCES public.products(id);

ALTER TABLE ONLY public.pos_sale_items
    ADD CONSTRAINT fkpodkiymphmr6a9ojjh32yqavd FOREIGN KEY (sale_id) REFERENCES public.pos_sales(id);

ALTER TABLE ONLY public.sale_return_refunds
    ADD CONSTRAINT fkqd2bkmadaqpsgpvmssttak2hj FOREIGN KEY (return_id) REFERENCES public.sale_returns(id);

ALTER TABLE ONLY public.staff_ledger_entries
    ADD CONSTRAINT fkqerey9hg96ixh3uvt094sa32q FOREIGN KEY (staff_id) REFERENCES public.staff(id);

ALTER TABLE ONLY public.sale_returns
    ADD CONSTRAINT fkqq2dtrsxys80h34o9ibec9ble FOREIGN KEY (sale_id) REFERENCES public.pos_sales(id);

    ELSE
        SELECT pg_get_indexdef(i.indexrelid) INTO v_index_definition
        FROM pg_index i
        JOIN pg_class x ON x.oid = i.indexrelid
        JOIN pg_namespace n ON n.oid = x.relnamespace
        WHERE n.nspname = 'public'
          AND x.relname = 'idx_audit_events_shop_created';

        IF v_index_definition IS NOT NULL
           AND v_index_definition <> 'CREATE INDEX idx_audit_events_shop_created ON public.audit_events USING btree (shop_id, created_at)' THEN
            RAISE EXCEPTION 'Existing idx_audit_events_shop_created is not the expected audit lookup index';
        END IF;

        SELECT pg_get_indexdef(i.indexrelid) INTO v_index_definition
        FROM pg_index i
        JOIN pg_class x ON x.oid = i.indexrelid
        JOIN pg_namespace n ON n.oid = x.relnamespace
        WHERE n.nspname = 'public'
          AND x.relname = 'idx_audit_shop_time';

        IF v_index_definition IS NOT NULL
           AND v_index_definition <> 'CREATE INDEX idx_audit_shop_time ON public.audit_events USING btree (shop_id, created_at)' THEN
            RAISE EXCEPTION 'Existing idx_audit_shop_time is not the expected audit lookup index';
        END IF;

        IF v_index_definition IS NULL THEN
            IF to_regclass('public.idx_audit_events_shop_created') IS NOT NULL THEN
                ALTER INDEX public.idx_audit_events_shop_created RENAME TO idx_audit_shop_time;
            ELSE
                CREATE INDEX idx_audit_shop_time ON public.audit_events USING btree (shop_id, created_at);
            END IF;
        ELSIF to_regclass('public.idx_audit_events_shop_created') IS NOT NULL THEN
            DROP INDEX public.idx_audit_events_shop_created;
        END IF;

        SELECT count(*) INTO v_application_tables
        FROM pg_tables
        WHERE schemaname = 'public'
          AND tablename <> 'flyway_schema_history';

        SELECT count(*) INTO v_application_columns
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name <> 'flyway_schema_history';

        SELECT count(*) INTO v_constraints
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'public'
          AND t.relkind = 'r'
          AND t.relname <> 'flyway_schema_history';

        SELECT count(*) INTO v_indexes
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename <> 'flyway_schema_history';

        SELECT count(*) INTO v_uuid_defaults
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name <> 'flyway_schema_history'
          AND udt_name = 'uuid'
          AND column_default IS NOT NULL;

        WITH objects AS (
            SELECT 'T|' || c.relname AS item
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind = 'r'
              AND c.relname <> 'flyway_schema_history'
            UNION ALL
            SELECT 'C|' || c.relname || '|' || a.attnum || '|' || a.attname || '|' ||
                   format_type(a.atttypid, a.atttypmod) || '|' || a.attnotnull || '|' ||
                   coalesce(pg_get_expr(d.adbin, d.adrelid), '') || '|' ||
                   a.attidentity::text || '|' || a.attgenerated::text
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
            WHERE n.nspname = 'public'
              AND c.relkind = 'r'
              AND c.relname <> 'flyway_schema_history'
              AND a.attnum > 0
              AND NOT a.attisdropped
            UNION ALL
            SELECT 'K|' || t.relname || '|' || c.conname || '|' || c.contype::text || '|' ||
                   pg_get_constraintdef(c.oid, true) || '|' || c.convalidated || '|' ||
                   c.condeferrable || '|' || c.condeferred
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public'
              AND t.relkind = 'r'
              AND t.relname <> 'flyway_schema_history'
            UNION ALL
            SELECT 'I|' || t.relname || '|' || i.relname || '|' || pg_get_indexdef(x.indexrelid) ||
                   '|' || x.indisvalid || '|' || x.indisready
            FROM pg_index x
            JOIN pg_class i ON i.oid = x.indexrelid
            JOIN pg_class t ON t.oid = x.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public'
              AND t.relkind = 'r'
              AND t.relname <> 'flyway_schema_history'
        )
        SELECT md5(string_agg(item, E'\n' ORDER BY item))
        INTO v_schema_fingerprint
        FROM objects;

        IF v_application_tables <> 29
           OR v_application_columns <> 283
           OR v_constraints <> 95
           OR v_indexes <> 69
           OR v_uuid_defaults <> 0
           OR v_schema_fingerprint <> 'f0535d43bd0f83c0976e8507cb2e1d2e' THEN
            RAISE EXCEPTION 'Existing application schema does not match the validated V4 schema (tables %, columns %, constraints %, indexes %, UUID defaults %, fingerprint %)',
                v_application_tables, v_application_columns, v_constraints, v_indexes,
                v_uuid_defaults, v_schema_fingerprint;
        END IF;
    END IF;
END
$migration$;
