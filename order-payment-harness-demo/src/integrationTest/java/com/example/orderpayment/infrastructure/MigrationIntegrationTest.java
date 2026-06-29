package com.example.orderpayment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationIntegrationTest {

    static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:order_payment_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void createsOrderPaymentTables() {
        List<String> tableNames = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """,
                String.class);

        assertThat(tableNames)
                .contains("orders", "order_items", "payment_transactions");
    }

    @Test
    void acceptsDefinedOrderStatuses() {
        UUID orderId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO orders (id, user_id, order_status, payable_amount)
                VALUES (?, ?, ?, ?)
                """,
                orderId,
                "user-1",
                "PENDING_PAYMENT",
                new BigDecimal("19.90"));

        String status = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE id = ?",
                String.class,
                orderId);

        assertThat(status).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void rejectsUndefinedOrderStatus() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO orders (id, user_id, order_status, payable_amount)
                VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                "user-1",
                "REFUNDED",
                new BigDecimal("19.90")))
                .isInstanceOf(Exception.class);
    }

    @Test
    void enforcesUniquePaymentTransactionId() {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO orders (id, user_id, order_status, payable_amount)
                VALUES (?, ?, ?, ?)
                """,
                orderId,
                "user-1",
                "PAID",
                new BigDecimal("19.90"));

        insertPaymentTransaction(UUID.randomUUID(), orderId, "txn-1");

        assertThatThrownBy(() -> insertPaymentTransaction(UUID.randomUUID(), orderId, "txn-1"))
                .isInstanceOf(Exception.class);
    }

    private void insertPaymentTransaction(UUID id, UUID orderId, String transactionId) {
        jdbcTemplate.update(
                """
                INSERT INTO payment_transactions (id, order_id, transaction_id, paid_amount, paid_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                orderId,
                transactionId,
                new BigDecimal("19.90"),
                Timestamp.from(Instant.parse("2026-06-29T12:00:00Z")));
    }
}
