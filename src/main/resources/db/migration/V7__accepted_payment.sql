CREATE TABLE if not exists event_payment_methods (
    id uuid primary key default gen_random_uuid(),
    id_event uuid not null references events(id) on delete cascade,
    payment_type VARCHAR(50) NOT NULL, -- e.g., BANK_TRANSFER, MOBILE_MONEY
    provider_name VARCHAR(100) NOT NULL, -- e.g., 'Bank of America', 'M-Pesa'
    account_name VARCHAR(150) NOT NULL, -- Name on the account
    account_identifier VARCHAR(100) NOT NULL, -- Account number, phone, or wallet address
    routing_details VARCHAR(100) NULL, -- SWIFT, IBAN, or Routing Number if applicable
    instructions TEXT NULL, -- Custom instructions for the buyer
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);