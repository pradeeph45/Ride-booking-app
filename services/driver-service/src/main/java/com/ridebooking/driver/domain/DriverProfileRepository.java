package com.ridebooking.driver.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverProfileRepository extends JpaRepository<DriverProfile, UUID> {}
