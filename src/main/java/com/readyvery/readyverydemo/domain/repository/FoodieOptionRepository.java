package com.readyvery.readyverydemo.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.readyvery.readyverydemo.domain.FoodieOption;

public interface FoodieOptionRepository extends JpaRepository<FoodieOption, Long> {
}
