package com.walletledger.domain.reward;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reward_programs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RewardProgram {

    @Id
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private long amount;
}
