package com.ridebooking.booking.application.matching;

import com.ridebooking.booking.domain.ServiceType;
import java.util.List;

/** Port: who is available near a point. The real adapter calls driver-service; tests use a fake. */
public interface DriverDirectory {

    /** Live drivers of {@code type} within {@code radiusKm} of the point, nearest first. */
    List<NearbyDriver> nearby(ServiceType type, double lat, double lng, double radiusKm, int limit);
}
