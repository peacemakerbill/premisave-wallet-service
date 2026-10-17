package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.Limits;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Money;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Enforces merchant and app ceilings in USD: single transaction, daily and
 * monthly volume (payments, payouts and transfers together) and daily payout count.
 * The day and month run in UTC. Volume is summed from the merchant's own
 * transactions that have not failed.
 */
@Service("commercialLimitService")
public class LimitService {

    private static final List<String> COUNTED = List.of(
            TxStatus.REQUIRES_ACTION.name(), TxStatus.PENDING.name(), TxStatus.SUCCESS.name());

    private final MongoTemplate mongo;

    public LimitService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void check(CommercialContext ctx, TxType type, long usdCents) {
        Limits limits = Limits.stricter(ctx.merchant().limitsFor(ctx.environment()), ctx.app().limits());
        if (limits.isEmpty()) return;

        if (exceeds(usdCents, limits.getSingleTransactionUsd())) {
            throw exceeded("singleTransactionUsd", limits.getSingleTransactionUsd());
        }

        // A settlement moves the merchant's own money to a destination fixed in advance, so only the
        // single transaction ceiling applies to it; volume limits are for money going to other parties.
        if (type == TxType.SETTLEMENT) return;

        Instant now = Instant.now();
        if (limits.getDailyVolumeUsd() != null || limits.getDailyPayoutCount() != null) {
            Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            List<CommercialTransaction> today = since(ctx, dayStart);
            if (limits.getDailyVolumeUsd() != null && exceeds(sum(today) + usdCents, limits.getDailyVolumeUsd())) {
                throw exceeded("dailyVolumeUsd", limits.getDailyVolumeUsd());
            }
            if (type == TxType.PAYOUT && limits.getDailyPayoutCount() != null) {
                long payouts = today.stream().filter(t -> t.getType() == TxType.PAYOUT).count();
                if (payouts + 1 > limits.getDailyPayoutCount()) {
                    throw new CommercialException(ErrorCode.LIMIT_EXCEEDED, "Daily payout count limit reached")
                            .with("limit", "dailyPayoutCount").with("max", limits.getDailyPayoutCount());
                }
            }
        }
        if (limits.getMonthlyVolumeUsd() != null) {
            ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
            Instant monthStart = utc.toLocalDate().withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            if (exceeds(sum(since(ctx, monthStart)) + usdCents, limits.getMonthlyVolumeUsd())) {
                throw exceeded("monthlyVolumeUsd", limits.getMonthlyVolumeUsd());
            }
        }
    }

    private List<CommercialTransaction> since(CommercialContext ctx, Instant from) {
        Criteria c = Criteria.where("merchantId").is(ctx.merchantId())
                .and("livemode").is(ctx.livemode())
                .and("type").in(List.of(TxType.PAYMENT.name(), TxType.PAYOUT.name(), TxType.TRANSFER.name()))
                .and("status").in(COUNTED)
                .and("createdAt").gte(from);
        return mongo.find(Query.query(c), CommercialTransaction.class);
    }

    private static long sum(List<CommercialTransaction> txs) {
        return txs.stream().mapToLong(CommercialTransaction::getAmountCents).sum();
    }

    private static boolean exceeds(long cents, BigDecimal ceilingUsd) {
        return ceilingUsd != null && cents > Money.toCents(ceilingUsd);
    }

    private static CommercialException exceeded(String which, BigDecimal max) {
        return new CommercialException(ErrorCode.LIMIT_EXCEEDED, "Amount exceeds the " + which + " limit")
                .with("limit", which).with("max", max.toPlainString());
    }
}
