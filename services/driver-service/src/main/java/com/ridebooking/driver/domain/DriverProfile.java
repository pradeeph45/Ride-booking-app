package com.ridebooking.driver.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A driver's vehicle and availability. The primary key is the auth-service user id, so there is exactly
 * one profile per driver and no way to read or change someone else's: the id always comes from the token.
 */
@Entity
@Table(name = "driver_profiles")
public class DriverProfile {

    private static final Pattern PLATE = Pattern.compile("[A-Z0-9]{4,15}");

    @Id
    @Column(name = "user_id", columnDefinition = "BINARY(16)")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "plate_number", nullable = false, unique = true, length = 16)
    private String plateNumber;

    @Column(name = "vehicle_model", nullable = false, length = 60)
    private String vehicleModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DriverStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected DriverProfile() {
        // for JPA
    }

    public static DriverProfile register(UUID userId, VehicleType type, String plate, String model, Instant now) {
        DriverProfile p = new DriverProfile();
        p.userId = userId;
        p.vehicleType = requireType(type);
        p.plateNumber = normalizePlate(plate);
        p.vehicleModel = requireModel(model);
        p.status = DriverStatus.OFFLINE;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /** True when this is exactly the vehicle already on file, so a repeated submit can be ignored. */
    public boolean isSameVehicle(VehicleType type, String plate, String model) {
        return this.vehicleType == type && this.plateNumber.equals(normalizePlate(plate)) && this.vehicleModel.equals(requireModel(model));
    }

    /** @return true if the vehicle TYPE changed (callers must then drop the driver from the old geo index) */
    public boolean updateVehicle(VehicleType type, String plate, String model, Instant now) {
        boolean typeChanged = requireType(type) != this.vehicleType;
        this.vehicleType = type;
        this.plateNumber = normalizePlate(plate);
        this.vehicleModel = requireModel(model);
        this.updatedAt = now;
        return typeChanged;
    }

    /** @return false when already online (a repeat changes nothing) */
    public boolean goOnline(Instant now) {
        return changeStatus(DriverStatus.ONLINE, now);
    }

    public boolean goOffline(Instant now) {
        return changeStatus(DriverStatus.OFFLINE, now);
    }

    private boolean changeStatus(DriverStatus next, Instant now) {
        if (status == next) {
            return false;
        }
        status = next;
        updatedAt = now;
        return true;
    }

    private static VehicleType requireType(VehicleType type) {
        if (type == null) {
            throw new IllegalArgumentException("Vehicle type is required");
        }
        return type;
    }

    private static String requireModel(String model) {
        String m = model == null ? "" : model.trim();
        if (m.isEmpty() || m.length() > 60) {
            throw new IllegalArgumentException("Vehicle model is required (up to 60 characters)");
        }
        return m;
    }

    /** "ka-01-ab-1234", " KA 01 AB 1234 " and "KA01AB1234" are one plate: upper-case, with all spaces and hyphens removed. */
    static String normalizePlate(String plate) {
        String p = plate == null ? "" : plate.trim().toUpperCase(Locale.ROOT).replaceAll("[ -]", "");
        if (!PLATE.matcher(p).matches()) {
            throw new IllegalArgumentException("Plate number must be 4-15 letters or digits");
        }
        return p;
    }

    public UUID getUserId() { return userId; }
    public VehicleType getVehicleType() { return vehicleType; }
    public String getPlateNumber() { return plateNumber; }
    public String getVehicleModel() { return vehicleModel; }
    public DriverStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
