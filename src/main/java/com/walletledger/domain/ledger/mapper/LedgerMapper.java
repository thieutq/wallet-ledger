package com.walletledger.domain.ledger.mapper;

import com.walletledger.domain.ledger.Hold;
import com.walletledger.domain.ledger.Transfer;
import com.walletledger.domain.ledger.dto.HoldResponse;
import com.walletledger.domain.ledger.dto.TransferResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface LedgerMapper {

    @Mapping(target = "status", expression = "java(transfer.getStatus().name())")
    @Mapping(target = "type", expression = "java(transfer.getType().name())")
    TransferResponse toResponse(Transfer transfer);

    @Mapping(target = "status", expression = "java(hold.getStatus().name())")
    @Mapping(target = "type", expression = "java(hold.getType().name())")
    HoldResponse toResponse(Hold hold);
}
