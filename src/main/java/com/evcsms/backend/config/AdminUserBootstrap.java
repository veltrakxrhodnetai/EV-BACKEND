package com.evcsms.backend.config;

import com.evcsms.backend.model.AdminUser;
import com.evcsms.backend.repository.AdminUserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminUserBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(AdminUserBootstrap.class);

    // SHA-256 for "Admin@123"
    private static final String DEFAULT_PASSWORD_HASH = "e86f78a8a3caf0b60d8e74e5942aa6d86dc150cd3c03338aef25b7d2d7e3acc7";

    private final AdminUserRepository adminUserRepository;

    public AdminUserBootstrap(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @PostConstruct
    @Transactional
    public void ensureDefaultAdminUsers() {
        upsertAdmin("superadmin", "Super Admin", "SUPER_ADMIN");
        upsertAdmin("admin", "Network Admin", "ADMIN");
    }

    private void upsertAdmin(String username, String fullName, String role) {
        AdminUser user = adminUserRepository.findByUsername(username).orElseGet(AdminUser::new);

        user.setUsername(username);
        user.setFullName(fullName);
        user.setRole(role);
        user.setStatus("ACTIVE");
        user.setPasswordHash(DEFAULT_PASSWORD_HASH);

        adminUserRepository.save(user);
        logger.info("Ensured admin user '{}' with role {} is ACTIVE", username, role);
    }
}
