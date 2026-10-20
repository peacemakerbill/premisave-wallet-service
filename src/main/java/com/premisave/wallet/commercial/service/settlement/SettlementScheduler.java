package com.premisave.wallet.commercial.service.settlement;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.entity.SettlementDestination;
import com.premisave.wallet.commercial.entity.settlement.SettlementSchedule;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.repository.settlement.SettlementScheduleRepository;
import com.premisave.wallet.commercial.security.AppContext;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.AppSnapshotService;
import com.premisave.wallet.commercial.service.FeeService;
import com.premisave.wallet.commercial.service.LedgerService;
import com.premisave.wallet.commercial.util.Ids;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Runs the settlement schedules merchants have saved. Each period is claimed
 * with a guarded update before anything is created, so two instances of the
 * service, or a restart in the middle of a run, cannot settle the same period
 * twice. A run that fails (for example the balance moved) is logged and
 * waits for the next period; it is not retried in a loop.
 */
@Component("commercialSettlementScheduler")
@ConditionalOnProperty(prefix = "commercial.settlements", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementScheduleRepository schedules;
    private final MerchantProfileRepository merchants;
    private final AppSnapshotService apps;
    private final LedgerService ledger;
    private final FeeService fees;
    private final SettlementService settlements;
    private final MongoTemplate mongo;

    public SettlementScheduler(SettlementScheduleRepository schedules, MerchantProfileRepository merchants,
                               AppSnapshotService apps, LedgerService ledger, FeeService fees,
                               SettlementService settlements, MongoTemplate mongo) {
        this.schedules = schedules;
        this.merchants = merchants;
        this.apps = apps;
        this.ledger = ledger;
        this.fees = fees;
        this.settlements = settlements;
        this.mongo = mongo;
    }

    @Scheduled(fixedDelayString = "${commercial.settlements.scheduler-interval-ms:60000}")
    public void run() {
        for (SettlementSchedule s : schedules.findByModeNot(SettlementSchedule.Mode.MANUAL)) {
            try {
                runOne(s);
            } catch (RuntimeException e) {
                log.error("Settlement schedule {} failed", s.getId(), e);
            }
        }
    }

    void runOne(SettlementSchedule s) {
        Optional<MerchantProfile> merchant = merchants.findById(s.getMerchantId());
        Optional<AppContext> app = apps.find(s.getAppId());
        if (merchant.isEmpty() || app.isEmpty()) return;
        Environment env = s.isLivemode() ? Environment.LIVE : Environment.SANDBOX;
        if (merchant.get().statusFor(env) != MerchantStatus.ACTIVE || !app.get().active()) return;

        SettlementDestination dest = merchant.get().destinationsFor(env).stream()
                .filter(d -> d.getId().equals(s.getDestinationId())).findFirst().orElse(null);
        if (dest == null) {
            log.warn("Settlement schedule {} names a destination that no longer exists", s.getId());
            return;
        }

        long available = ledger.wallet(merchant.get().walletIdFor(env)).getAvailableCents();
        SettlementPlanner.Decision decision = SettlementPlanner.decide(s, Instant.now(), available);
        if (!decision.due()) return;
        if (s.getMode() == SettlementSchedule.Mode.THRESHOLD && hasOpenScheduledSettlement(s)) return;
        if (!claim(s, decision.runKey())) return;

        long amount = SettlementPlanner.amountFor(s, available,
                cents -> fees.fee(Direction.PAYOUT, dest.getType(), cents));
        if (amount <= 0) {
            log.debug("Settlement schedule {} has nothing to settle for {}", s.getId(), decision.runKey());
            return;
        }
        CommercialContext ctx = new CommercialContext(env, merchant.get(), app.get(), Ids.requestId());
        try {
            String id = settlements.startScheduled(ctx, dest, amount, "SCHEDULE-" + decision.runKey());
            log.info("Scheduled settlement {} created for merchant {} ({})", id, s.getMerchantId(), decision.runKey());
        } catch (CommercialException e) {
            log.warn("Scheduled settlement for merchant {} ({}) was not created: {}", s.getMerchantId(),
                    decision.runKey(), e.getMessage());
        }
    }

    /** True for exactly one caller per period. */
    private boolean claim(SettlementSchedule s, String runKey) {
        Criteria c = Criteria.where("id").is(s.getId()).and("lastRunKey").ne(runKey);
        Document set = new Document("lastRunKey", runKey).append("lastRunAt", Date.from(Instant.now()));
        SettlementSchedule claimed = mongo.findAndModify(Query.query(c), new BasicUpdate(new Document("$set", set)),
                FindAndModifyOptions.options().returnNew(true), SettlementSchedule.class);
        return claimed != null;
    }

    private boolean hasOpenScheduledSettlement(SettlementSchedule s) {
        Criteria c = Criteria.where("merchantId").is(s.getMerchantId())
                .and("livemode").is(s.isLivemode())
                .and("type").is(TxType.SETTLEMENT.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("relations.trigger").is(SettlementService.TRIGGER_SCHEDULE);
        return mongo.count(Query.query(c), CommercialTransaction.class) > 0;
    }
}
