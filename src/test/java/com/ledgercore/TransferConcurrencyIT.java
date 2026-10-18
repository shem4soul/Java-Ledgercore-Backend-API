package com.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ledgercore.account.Account;
import com.ledgercore.common.BusinessException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Financial correctness under load: money is conserved, balances never go negative, nothing deadlocks. */
class TransferConcurrencyIT extends AbstractIT {

    @Test
    void manyConcurrentRandomTransfers_conserveMoney_andNeverOverdraw() throws Exception {
        long initial = 100_000;
        List<Customer> customers = new ArrayList<>();
        for (int i = 0; i < 10; i++) customers.add(customer(initial));

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            futures.add(pool.submit(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                int a = rnd.nextInt(customers.size());
                int b = (a + 1 + rnd.nextInt(customers.size() - 1)) % customers.size();
                try {
                    send(customers.get(a), customers.get(b).account(), 1 + rnd.nextLong(30_000), "k-" + UUID.randomUUID());
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.code().equals("INSUFFICIENT_FUNDS")) insufficient.incrementAndGet();
                    else unexpected.add(e);
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }));
        }
        for (Future<?> f : futures) f.get(120, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(unexpected).as("no deadlocks, lock timeouts or other unexpected failures").isEmpty();
        assertThat(ok.get() + insufficient.get()).isEqualTo(400);
        assertThat(ok.get()).as("some transfers must actually have succeeded").isPositive();

        long total = 0;
        for (Customer c : customers) {
            long bal = balance(c.account());
            assertThat(bal).as("no account may be overdrawn").isGreaterThanOrEqualTo(0);
            total += bal;
        }
        assertThat(total).as("money is neither created nor destroyed").isEqualTo(initial * customers.size());

        Long entries = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE account_id = ANY(?::uuid[])", Long.class,
                (Object) customers.stream().map(c -> c.account().getId().toString()).toArray(String[]::new));
        assertThat(entries).as("2 entries per transfer + 1 per seed deposit").isEqualTo(2L * ok.get() + customers.size());

        var report = reconciliation.run();
        assertThat(report.healthy()).as("reconciliation: %s", report).isTrue();
    }

    @Test
    void opposingTransfers_doNotDeadlock() {
        Customer a = customer(10_000_000);
        Customer b = customer(10_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    final boolean forward = i % 2 == 0;
                    fs.add(pool.submit(() -> {
                        try {
                            if (forward) send(a, b.account(), 10, "k-" + UUID.randomUUID());
                            else send(b, a.account(), 10, "k-" + UUID.randomUUID());
                        } catch (Throwable t) {
                            failures.add(t);
                        }
                    }));
                }
                for (Future<?> f : fs) f.get();
            });
        } finally {
            pool.shutdown();
        }
        assertThat(failures).as("A->B and B->A in parallel must all succeed (ordered locking)").isEmpty();
        assertThat(balance(a.account()) + balance(b.account())).isEqualTo(20_000_000);
    }

    @Test
    void hotAccount_onlyAffordableDebitsSucceed() throws Exception {
        Customer rich = customer(100_000);
        Customer sink = customer(0);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fs.add(pool.submit(() -> {
                try {
                    send(rich, sink.account(), 10_000, "k-" + UUID.randomUUID());
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.code().equals("INSUFFICIENT_FUNDS")) insufficient.incrementAndGet();
                    else unexpected.add(e);
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(unexpected).isEmpty();
        assertThat(ok.get()).as("exactly 10 x 10,000 fit into 100,000").isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(40);
        assertThat(balance(rich.account())).isZero();
        assertThat(balance(sink.account())).isEqualTo(100_000);
    }

    @Test
    void dailyLimit_isEnforcedAtomically_underConcurrency() throws Exception {
        Customer payer = customer(1_000_000);
        Customer payee = customer(0);
        accountService.setDailyLimit(adminId(), payer.account().getId(), 50_000);   // 3 x 20,000 would exceed it

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fs.add(pool.submit(() -> {
                try {
                    send(payer, payee.account(), 20_000, "k-" + UUID.randomUUID());
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.code().equals("DAILY_LIMIT_EXCEEDED")) limited.incrementAndGet();
                    else unexpected.add(e);
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(unexpected).isEmpty();
        assertThat(ok.get()).isEqualTo(2);
        assertThat(limited.get()).isEqualTo(1);
        assertThat(balance(payee.account())).isEqualTo(40_000);
    }
}
