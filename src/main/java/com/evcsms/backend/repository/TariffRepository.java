package com.evcsms.backend.repository;

import com.evcsms.backend.model.Tariff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TariffRepository extends JpaRepository<Tariff, Long> {

    @Query("select distinct t from Tariff t join fetch t.station s left join fetch t.charger c")
    List<Tariff> findAllWithRelations();

    @Query("select t from Tariff t join fetch t.station s left join fetch t.charger c where s.id = :stationId")
    Optional<Tariff> findByStation_Id(Long stationId);

    boolean existsByStation_Id(Long stationId);

    @Modifying
    @Transactional
    long deleteByStation_Id(Long stationId);
}
