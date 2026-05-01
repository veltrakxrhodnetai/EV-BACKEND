package com.evcsms.backend.aspect;

import com.evcsms.backend.service.OwnerAuthService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Aspect
@Component
public class OwnerStationAccessAspect {

    private static final Logger logger = LoggerFactory.getLogger(OwnerStationAccessAspect.class);

    private final OwnerAuthService ownerAuthService;

    public OwnerStationAccessAspect(OwnerAuthService ownerAuthService) {
        this.ownerAuthService = ownerAuthService;
    }

    /**
        * Validates that owner accessing a session belongs to the same station as the charger
     * This is a utility method pattern - can be called from controllers to validate access
     */
    public void validateOwnerStationAccess(Long stationId, String authorizationHeader) throws IllegalAccessException {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            // Not an owner token, allow (could be customer or public endpoint)
            return;
        }

        try {
            OwnerAuthService.AuthenticatedOwner owner = ownerAuthService.requireOwnerFromAuthorizationHeader(authorizationHeader);
            
            if (!owner.canAccessStation(stationId)) {
                logger.warn("Access denied: Owner {} attempted to access station {} which is not assigned to them", 
                    owner.ownerId(), stationId);
                throw new IllegalAccessException("Access denied: You do not have access to this station");
            }
            
            logger.debug("Owner {} granted access to station {}", owner.ownerId(), stationId);
        } catch (IllegalArgumentException ex) {
            // Invalid token, let it pass - security layer will handle it
            logger.debug("Token validation failed: {}", ex.getMessage());
        }
    }

    /**
     * Extract and validate owner access for resource
     * Returns null if no owner token present
     */
    public OwnerAuthService.AuthenticatedOwner getOwnerIfPresent(String authorizationHeader) {
        logger.debug("getOwnerIfPresent called with header: {}", authorizationHeader != null ? "present" : "null");
        
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            if (authorizationHeader == null) {
                logger.debug("Authorization header is null");
            } else {
                logger.debug("Authorization header doesn't start with Bearer: {}", authorizationHeader.substring(0, Math.min(20, authorizationHeader.length())));
            }
            return null;
        }

        try {
            OwnerAuthService.AuthenticatedOwner owner = ownerAuthService.requireOwnerFromAuthorizationHeader(authorizationHeader);
            logger.debug("Owner parsed successfully: {}", owner.ownerId());
            return owner;
        } catch (IllegalArgumentException ex) {
            logger.debug("Could not parse owner token: {}", ex.getMessage());
            return null;
        }
    }
}
