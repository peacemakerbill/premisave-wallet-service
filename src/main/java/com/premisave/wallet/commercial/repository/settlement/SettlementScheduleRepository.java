package com.premisave.wallet.commercial.repository.settlement;

import com.premisave.wallet.commercial.entity.settlement.SettlementSchedule;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SettlementScheduleRepository extends MongoRepository<SettlementSchedule, String> {
    List<SettlementSchedule> findByModeNot(SettlementSchedule.Mode mode);
}
