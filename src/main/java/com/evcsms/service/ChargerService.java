package com.evcsms.service;

import com.evcsms.model.Charger;
import com.evcsms.repository.ChargerRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChargerService {

    private final ChargerRepository chargerRepository;

    public ChargerService(ChargerRepository chargerRepository) {
        this.chargerRepository = chargerRepository;
    }

    public List<Charger> getAllChargers() {
        return chargerRepository.findAll();
    }
}
