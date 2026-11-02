package com.gatekeeper.qaplatform.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByIssuerAndSubject(String issuer, String subject);

    List<AppUser> findAllByOrderByEmailAsc();
}
