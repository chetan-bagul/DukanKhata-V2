-- Add reconciliation fields only when the legacy tables exist.
-- Fresh installations can create the tables through Hibernate afterward.

DO $$
BEGIN
    IF to_regclass('public.customer_ledger_entries') IS NOT NULL THEN
        ALTER TABLE public.customer_ledger_entries
            ADD COLUMN IF NOT EXISTS payment_method VARCHAR(20);
    END IF;

    IF to_regclass('public.daily_closings') IS NOT NULL THEN
        ALTER TABLE public.daily_closings
            ADD COLUMN IF NOT EXISTS pos_cash NUMERIC(14,2) NOT NULL DEFAULT 0,
            ADD COLUMN IF NOT EXISTS pos_upi NUMERIC(14,2) NOT NULL DEFAULT 0,
            ADD COLUMN IF NOT EXISTS pos_udhar NUMERIC(14,2) NOT NULL DEFAULT 0,
            ADD COLUMN IF NOT EXISTS customer_repayment_cash NUMERIC(14,2) NOT NULL DEFAULT 0,
            ADD COLUMN IF NOT EXISTS customer_repayment_upi NUMERIC(14,2) NOT NULL DEFAULT 0;
    END IF;
END $$;