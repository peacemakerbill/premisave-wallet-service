package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.dto.CreateCustomerRequest;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Customers (sections 8.5 to 8.7): the people a merchant bills. */
@Service("commercialCustomerService")
public class CustomerService {

    private final BillingCustomerRepository customers;
    private final CustomerPaymentMethodRepository methods;
    private final MongoTemplate mongo;

    public CustomerService(BillingCustomerRepository customers, CustomerPaymentMethodRepository methods, MongoTemplate mongo) {
        this.customers = customers;
        this.methods = methods;
        this.mongo = mongo;
    }

    public Map<String, Object> create(CommercialContext ctx, CreateCustomerRequest req) {
        BillingCustomer c = new BillingCustomer();
        c.setCustomerId(Ids.next("cus"));
        c.setMerchantId(ctx.merchantId());
        c.setLivemode(ctx.livemode());
        c.setName(req.name().trim());
        if (req.email() != null && !req.email().isBlank()) {
            if (!RequestValidators.isEmail(req.email().trim())) throw CommercialException.validation("email", "is not a valid email address");
            c.setEmail(req.email().trim());
        }
        if (req.phone() != null && !req.phone().isBlank()) c.setPhone(RequestValidators.normalisePhone("phone", req.phone()));
        if (req.merchantReference() != null && !req.merchantReference().isBlank()) {
            c.setMerchantReference(req.merchantReference().trim());
        }
        c.setMetadata(RequestValidators.metadata(req.metadata()));
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        try {
            customers.insert(c);
        } catch (DuplicateKeyException e) {
            BillingCustomer existing = customers.findByReferenceKey(c.getReferenceKey()).orElse(null);
            CommercialException ex = new CommercialException(ErrorCode.DUPLICATE_REQUEST,
                    "A customer with merchantReference " + c.getMerchantReference() + " already exists");
            if (existing != null) ex.with("customerId", existing.getCustomerId());
            throw ex;
        }
        return BillingViews.customer(c, List.of());
    }

    public Page<BillingCustomer> list(CommercialContext ctx, Map<String, String> f, Pageable pageable) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("merchantId").is(ctx.merchantId()));
        all.add(Criteria.where("livemode").is(ctx.livemode()));
        String email = f.get("email");
        if (email != null && !email.isBlank()) all.add(Criteria.where("email").is(email.trim()));
        String phone = f.get("phone");
        if (phone != null && !phone.isBlank()) all.add(Criteria.where("phone").is(RequestValidators.normalisePhone("phone", phone)));
        String ref = f.get("merchantReference");
        if (ref != null && !ref.isBlank()) all.add(Criteria.where("merchantReference").is(ref.trim()));
        Query q = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(q, BillingCustomer.class);
        return new PageImpl<>(mongo.find(q.with(pageable), BillingCustomer.class), pageable, total);
    }

    public BillingCustomer require(CommercialContext ctx, String customerId) {
        return customers.findByCustomerIdAndMerchantIdAndLivemode(customerId, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Customer"));
    }

    public Map<String, Object> view(BillingCustomer c) {
        return BillingViews.customer(c, methods.findByCustomerId(c.getCustomerId()));
    }

    public Map<String, Object> get(CommercialContext ctx, String customerId) {
        return view(require(ctx, customerId));
    }
}
