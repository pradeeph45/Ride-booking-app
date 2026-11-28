package com.ridebooking.booking.application;

import com.ridebooking.booking.domain.Place;
import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Prices a trip: max(minimum, base + km x perKm + minutes x perMinute), in rupees.
 * Distance is the straight line between the two points scaled up to approximate road distance;
 * a routing engine can replace {@link #roadDistanceKm} later without touching anything else.
 * Surge pricing and promotions will plug in as extra strategies.
 */
@Component
public class FareCalculator {

    private static final String CURRENCY = "INR";
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final double ROAD_FACTOR = 1.3;
    private static final double MIN_DISTANCE_KM = 0.1;
    /** A metered fare may exceed the quoted estimate by at most this factor. */
    public static final BigDecimal METERED_CAP = new BigDecimal("1.5");

    /** Per-service pricing: base fare, per km, per minute, minimum fare, average speed (km/h). */
    private record Policy(String base, String perKm, String perMinute, String minimum, double speedKmh) {}

    private static final Map<ServiceType, Policy> POLICIES = new EnumMap<>(ServiceType.class);

    static {
        POLICIES.put(ServiceType.BIKE, new Policy("20", "6", "0.50", "30", 28));
        POLICIES.put(ServiceType.AUTO, new Policy("30", "11", "1.00", "45", 22));
        POLICIES.put(ServiceType.CAR_MINI, new Policy("50", "12", "1.50", "80", 24));
        POLICIES.put(ServiceType.CAR_SEDAN, new Policy("60", "14", "1.75", "100", 24));
        POLICIES.put(ServiceType.CAR_SUV, new Policy("80", "18", "2.00", "150", 24));
    }

    public FareQuote estimate(ServiceType type, Place pickup, Place drop) {
        double km = roadDistanceKm(pickup, drop);
        if (km < MIN_DISTANCE_KM) {
            throw new IllegalArgumentException("Pickup and drop are too close together");
        }
        Policy policy = POLICIES.get(type);
        BigDecimal distance = BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP);
        int minutes = Math.max(1, (int) Math.ceil(km / policy.speedKmh() * 60));

        return new FareQuote(type, distance, minutes, price(policy, distance, minutes), CURRENCY);
    }

    /**
     * Prices a trip from what was actually driven, with the SAME rate card as the estimate. The result is never
     * above {@link #METERED_CAP} times the estimate: a detour or a doctored meter cannot run the rider's bill up
     * without limit, and the driver can never earn less than the service minimum.
     *
     * @param meters  distance driven between start and complete, as measured from GPS pings
     * @param seconds time between start and complete
     */
    public MeteredFare metered(ServiceType type, BigDecimal estimatedFare, double meters, long seconds) {
        Policy policy = POLICIES.get(type);
        BigDecimal distance = BigDecimal.valueOf(meters / 1000.0).setScale(2, RoundingMode.HALF_UP);
        int minutes = (int) Math.max(1, Math.ceil(seconds / 60.0));
        BigDecimal raw = price(policy, distance, minutes);
        BigDecimal ceiling = estimatedFare.multiply(METERED_CAP).setScale(2, RoundingMode.HALF_UP);
        boolean capped = raw.compareTo(ceiling) > 0;
        return new MeteredFare(distance, minutes, capped ? ceiling : raw, capped);
    }

    private static BigDecimal price(Policy policy, BigDecimal distance, int minutes) {
        return new BigDecimal(policy.base())
                .add(distance.multiply(new BigDecimal(policy.perKm())))
                .add(new BigDecimal(policy.perMinute()).multiply(BigDecimal.valueOf(minutes)))
                .max(new BigDecimal(policy.minimum()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    static double roadDistanceKm(Place a, Place b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        double straight = 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h)); // haversine
        return straight * ROAD_FACTOR;
    }
}
