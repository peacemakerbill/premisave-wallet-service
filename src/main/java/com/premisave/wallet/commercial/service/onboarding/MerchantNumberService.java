package com.premisave.wallet.commercial.service.onboarding;

import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.entity.onboarding.CommercialCounter;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Hands out merchant numbers one after another: PRM-0000000001, PRM-0000000002 and so on. A number is issued when an
 * onboarding application is approved, never before, so a number always means a business that was reviewed and accepted.
 *
 * The counter is a single atomic increment in the database, so two approvals at the same moment can never share a
 * number. Numbers are never reused. A number can be skipped only if the process stops between taking it and saving
 * it on the merchant.
 */
@Service("commercialMerchantNumberService")
public class MerchantNumberService {

    static final String COUNTER_ID = "merchant_number";

    private final MongoTemplate mongo;

    public MerchantNumberService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public String next() {
        CommercialCounter c = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(COUNTER_ID)),
                new BasicUpdate(new Document("$inc", new Document("seq", 1L))),
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                CommercialCounter.class);
        if (c == null) {
            throw new CommercialException(com.premisave.wallet.commercial.enums.ErrorCode.INTERNAL_ERROR,
                    "The merchant number counter could not be read");
        }
        return MerchantNumbers.format(c.getSeq());
    }

    /**
     * Gives the merchant its number if it has none, and returns it with the number set. A merchant that already has one
     * keeps it, so repeating an approval after a failure reuses the number instead of taking another. The write only
     * lands when the merchant still has none, so a concurrent caller cannot overwrite a number.
     */
    public MerchantProfile issue(MerchantProfile p) {
        if (p.getMerchantNumber() != null) return p;
        String number = next();
        var result = mongo.updateFirst(
                Query.query(Criteria.where("_id").is(p.getMerchantId()).and("merchantNumber").is(null)),
                new BasicUpdate(new Document("$set", new Document("merchantNumber", number))),
                MerchantProfile.class);
        if (result != null && result.getModifiedCount() > 0) {
            p.setMerchantNumber(number);
            return p;
        }
        MerchantProfile fresh = mongo.findById(p.getMerchantId(), MerchantProfile.class);
        if (fresh != null && fresh.getMerchantNumber() != null) {
            p.setMerchantNumber(fresh.getMerchantNumber());
        }
        return p;
    }

    /**
     * For a business that was switched on before numbers were issued at approval: its number is issued the first time it
     * is read. A business that is not live yet gets nothing here, its number comes with the approval.
     */
    public MerchantProfile issueIfLive(MerchantProfile p) {
        if (p.getMerchantNumber() != null || p.statusFor(Environment.LIVE) != MerchantStatus.ACTIVE) return p;
        return issue(p);
    }
}