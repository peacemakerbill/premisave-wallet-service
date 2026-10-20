package com.premisave.wallet.commercial.repository.link;

import com.premisave.wallet.commercial.entity.link.AccountPrefix;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountPrefixRepository extends MongoRepository<AccountPrefix, String> {
}
