package com.ridebooking.booking;

import com.ridebooking.booking.application.matching.DriverDirectory;
import com.ridebooking.booking.application.matching.DriverDirectoryException;
import com.ridebooking.booking.application.matching.NearbyDriver;
import com.ridebooking.booking.domain.ServiceType;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stands in for driver-service: tests place drivers on the map and matching asks who is near. */
public class FakeDriverDirectory implements DriverDirectory {

    private record Spot(ServiceType type, double lat, double lng) {}

    private final Map<UUID, Spot> drivers = new ConcurrentHashMap<>();
    private volatile boolean down;

    public void place(UUID driverId, ServiceType type, double lat, double lng) {
        drivers.put(driverId, new Spot(type, lat, lng));
    }

    public void remove(UUID driverId) {
        drivers.remove(driverId);
    }

    public void clear() {
        drivers.clear();
        down = false;
    }

    /** Simulates driver-service being unreachable. */
    public void setDown(boolean down) {
        this.down = down;
    }

    @Override
    public List<NearbyDriver> nearby(ServiceType type, double lat, double lng, double radiusKm, int limit) {
        if (down) {
            throw new DriverDirectoryException("driver-service is down (simulated)");
        }
        return drivers.entrySet().stream()
                .filter(e -> e.getValue().type() == type)
                .map(e -> new NearbyDriver(e.getKey(), e.getValue().lat(), e.getValue().lng(),
                        haversineKm(lat, lng, e.getValue().lat(), e.getValue().lng())))
                .filter(d -> d.distanceKm() <= radiusKm)
                .sorted(Comparator.comparingDouble(NearbyDriver::distanceKm))
                .limit(limit)
                .toList();
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6371.0088 * Math.asin(Math.sqrt(h));
    }
}
