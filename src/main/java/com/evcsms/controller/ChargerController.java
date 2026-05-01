package com.evcsms.controller;

import com.evcsms.model.Charger;
import com.evcsms.service.ChargerService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("liveChargerController")
@RequestMapping("/api/chargers")
@CrossOrigin("*")
public class ChargerController {

    private final ChargerService chargerService;

    public ChargerController(ChargerService chargerService) {
        this.chargerService = chargerService;
    }

    @GetMapping
    public List<Charger> getAllChargers() {
        return chargerService.getAllChargers();
    }
}
