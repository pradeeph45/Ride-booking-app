package com.ridebooking.booking.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingStatusChangeRepository extends JpaRepository<BookingStatusChange, UUID> {

    List<BookingStatusChange> findByBookingIdOrderByChangedAtAsc(UUID bookingId);
}
