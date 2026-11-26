package com.ridebooking.driver.application;

import com.ridebooking.driver.domain.DriverProfile;
import com.ridebooking.driver.domain.DriverProfileNotFoundException;
import com.ridebooking.driver.domain.DriverProfileRepository;
import com.ridebooking.driver.domain.PlateAlreadyRegisteredException;
import com.ridebooking.driver.domain.VehicleType;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** A driver's vehicle and online/offline switch. The driver id is always the caller's own, taken from the token. */
@Service
public class DriverProfileService {

    private final DriverProfileRepository profiles;
    private final DriverGeoIndex index;
    private final Clock clock;
    private final TransactionTemplate tx;

    public DriverProfileService(DriverProfileRepository profiles, DriverGeoIndex index, Clock clock, TransactionTemplate tx) {
        this.profiles = profiles;
        this.index = index;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * Creates or updates the caller's vehicle. Two things can collide in the database: the primary key (the
     * same driver double-submitting the first registration) and the unique plate (another driver's vehicle).
     * A collision poisons its transaction, so we retry in a fresh one: a primary-key race then becomes a plain
     * update (or, if the vehicle is identical, a no-op), while a genuine plate clash fails again and is reported.
     * A lost optimistic-lock race (two different simultaneous edits) is retried up to three times.
     */
    public UpsertResult upsert(UUID userId, VehicleType type, String plate, String model) {
        Upserted result;
        int integrityFailures = 0;
        int concurrencyFailures = 0;
        while (true) {
            try {
                result = tx.execute(s -> upsertOnce(userId, type, plate, model));
                break;
            } catch (DataIntegrityViolationException e) {
                if (++integrityFailures >= 2) {
                    throw new PlateAlreadyRegisteredException(); // failed twice: it really is another driver's plate
                }
            } catch (ConcurrencyFailureException e) {
                if (++concurrencyFailures >= 3) {
                    throw e; // keeps losing to genuinely different simultaneous edits: let the caller retry
                }
            }
        }
        if (result.typeChanged()) {
            index.remove(userId); // the old vehicle's geo set must not keep offering this driver; the next ping re-adds
        }
        return result.toResult();
    }

    private Upserted upsertOnce(UUID userId, VehicleType type, String plate, String model) {
        var now = clock.instant();
        var existing = profiles.findById(userId);
        if (existing.isPresent()) {
            DriverProfile p = existing.get();
            if (p.isSameVehicle(type, plate, model)) {
                return new Upserted(p, false, false); // identical resubmit: write nothing, so repeats and double-clicks cannot conflict
            }
            boolean typeChanged = p.updateVehicle(type, plate, model, now);
            return new Upserted(profiles.saveAndFlush(p), false, typeChanged);
        }
        return new Upserted(profiles.saveAndFlush(DriverProfile.register(userId, type, plate, model, now)), true, false);
    }

    private record Upserted(DriverProfile profile, boolean created, boolean typeChanged) {
        UpsertResult toResult() {
            return new UpsertResult(profile, created);
        }
    }

    public DriverProfile get(UUID userId) {
        return profiles.findById(userId).orElseThrow(DriverProfileNotFoundException::new);
    }

    public DriverProfile goOnline(UUID userId) {
        return tx.execute(s -> {
            DriverProfile p = profiles.findById(userId).orElseThrow(DriverProfileNotFoundException::new);
            return p.goOnline(clock.instant()) ? profiles.saveAndFlush(p) : p; // a repeat writes nothing
        });
    }

    public DriverProfile goOffline(UUID userId) {
        DriverProfile p = tx.execute(s -> {
            DriverProfile found = profiles.findById(userId).orElseThrow(DriverProfileNotFoundException::new);
            return found.goOffline(clock.instant()) ? profiles.saveAndFlush(found) : found;
        });
        index.remove(userId); // always clean up, even on a repeat, so a stale index entry cannot linger
        return p;
    }
}
