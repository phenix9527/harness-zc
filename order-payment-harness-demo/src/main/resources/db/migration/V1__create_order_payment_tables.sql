CREATE TABLE orders (
    id UUID PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    order_status VARCHAR(32) NOT NULL,
    payable_amount NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_orders_order_status
        CHECK (order_status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED')),
    CONSTRAINT chk_orders_payable_amount
        CHECK (payable_amount >= 0)
);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    sku_id VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price_snapshot NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT chk_order_items_quantity
        CHECK (quantity > 0),
    CONSTRAINT chk_order_items_unit_price_snapshot
        CHECK (unit_price_snapshot >= 0)
);

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    transaction_id VARCHAR(128) NOT NULL,
    paid_amount NUMERIC(19, 2) NOT NULL,
    paid_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_payment_transactions_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT uk_payment_transactions_transaction_id
        UNIQUE (transaction_id),
    CONSTRAINT chk_payment_transactions_paid_amount
        CHECK (paid_amount >= 0)
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_order_status ON orders (order_status);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_sku_id ON order_items (sku_id);
CREATE INDEX idx_payment_transactions_order_id ON payment_transactions (order_id);
