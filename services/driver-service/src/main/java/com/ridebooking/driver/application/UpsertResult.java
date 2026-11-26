package com.ridebooking.driver.application;

import com.ridebooking.driver.domain.DriverProfile;

public record UpsertResult(DriverProfile profile, boolean created) {}
