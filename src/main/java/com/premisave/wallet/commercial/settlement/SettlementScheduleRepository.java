package com.premisave.wallet.commercial.settlement;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SettlementScheduleRepository extends MongoRepository<SettlementSchedule, String> {
    List<SettlementSchedule> findByModeNot(SettlementSchedule.Mode mode);
}
