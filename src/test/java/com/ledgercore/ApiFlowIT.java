package com.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgercore.outbox.OutboxPublisher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The whole customer journey over HTTP, including idempotent replay, statements, notifications and the admin back office. */
class ApiFlowIT extends AbstractApiIT {
    @Autowired OutboxPublisher publisher;

    @Test
    void endToEnd_registerFundTransferReplayStatementNotifyReverseReconcile() throws Exception {
        String aliceToken = registerAndLogin(newEmail());
        String bobToken = registerAndLogin(newEmail());
        JsonNode alice = openAccount(aliceToken, "NGN");
        JsonNode bob = openAccount(bobToken, "NGN");
        String admin = adminToken();

        // fund Alice (admin deposit is itself idempotent)
        String depKey = "dep-" + UUID.randomUUID();
        Map<String, Object> deposit = Map.of("amountMinor", 250_000, "description", "cash-in");
        postWithKey("/api/v1/admin/accounts/" + alice.get("id").asText() + "/deposit", admin, depKey, deposit).andExpect(status().isCreated());
        postWithKey("/api/v1/admin/accounts/" + alice.get("id").asText() + "/deposit", admin, depKey, deposit)
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));

        // beneficiary confirmation shows a masked name only
        getJson("/api/v1/accounts/lookup/" + bob.get("accountNumber").asText(), aliceToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountName").value("A** T*****"));

        // transfer, then replay it
        String key = "pay-" + UUID.randomUUID();
        Map<String, Object> transfer = Map.of("fromAccountId", alice.get("id").asText(), "toAccountNumber", bob.get("accountNumber").asText(),
                "amountMinor", 100_000, "currency", "NGN", "description", "rent");
        JsonNode first = read(postWithKey("/api/v1/transfers", aliceToken, key, transfer).andExpect(status().isCreated()));
        JsonNode replay = read(postWithKey("/api/v1/transfers", aliceToken, key, transfer)
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true")));
        assertThat(replay.get("transactionId")).isEqualTo(first.get("transactionId"));
        assertThat(first.get("senderBalanceAfterMinor").asLong()).isEqualTo(150_000);

        getJson("/api/v1/accounts/" + alice.get("id").asText(), aliceToken).andExpect(jsonPath("$.balanceMinor").value(150_000));
        getJson("/api/v1/accounts/" + bob.get("id").asText(), bobToken).andExpect(jsonPath("$.balanceMinor").value(100_000));

        // statement shows the deposit and the debit, newest first, with the counterparty
        getJson("/api/v1/accounts/" + alice.get("id").asText() + "/transactions", aliceToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].counterpartyAccountNumber").value(bob.get("accountNumber").asText()));

        // transaction detail: visible to both parties, hidden from outsiders
        String txId = first.get("transactionId").asText();
        getJson("/api/v1/transactions/" + txId, bobToken).andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(2));
        getJson("/api/v1/transactions/" + txId, registerAndLogin(newEmail())).andExpect(status().isNotFound());

        // the outbox delivers notifications to both parties
        drainOtherEvents(txId);
        publisher.processBatch();
        getJson("/api/v1/notifications", aliceToken).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind=='DEBIT_ALERT')]").isNotEmpty());
        getJson("/api/v1/notifications", bobToken).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind=='CREDIT_ALERT')]").isNotEmpty());

        // back office: reverse it, reconcile, inspect audit trail and stats
        postWithKey("/api/v1/admin/transactions/" + txId + "/reverse", admin, "rev-" + UUID.randomUUID(), Map.of("reason", "dispute #1042"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("REVERSAL"));
        getJson("/api/v1/accounts/" + alice.get("id").asText(), aliceToken).andExpect(jsonPath("$.balanceMinor").value(250_000));
        postJson("/api/v1/admin/reconciliation/run", admin, Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.healthy").value(true));
        getJson("/api/v1/admin/audit-logs?action=TRANSACTION_REVERSED", admin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        getJson("/api/v1/admin/stats", admin).andExpect(status().isOk()).andExpect(jsonPath("$.users").isNumber());
    }

    @Test
    void errorResponses_areProblemJson_withStableCodes_andNeverLeakInternals() throws Exception {
        String token = registerAndLogin(newEmail());
        JsonNode acct = openAccount(token, "NGN");
        String body = postWithKey("/api/v1/transfers", token, "big-" + UUID.randomUUID(),
                Map.of("fromAccountId", acct.get("id").asText(), "toAccountNumber", "0000000001", "amountMinor", 1, "currency", "NGN"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("BENEFICIARY_NOT_FOUND").doesNotContain("Exception").doesNotContain("at com.");
        getJson("/api/v1/accounts/" + UUID.randomUUID(), token).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        getJson("/api/v1/accounts/not-a-uuid", token).andExpect(status().isBadRequest());
    }

    /** Marks every other pending event as sent so this test only asserts on its own. */
    private void drainOtherEvents(String keepTransactionId) {
        jdbc.update("UPDATE outbox_events SET status = 'SENT' WHERE status <> 'SENT' AND aggregate_id <> ?::uuid", keepTransactionId);
    }
}
