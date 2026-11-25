package com.ridebooking.booking.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    /** Open requests, oldest first, so waiting riders are served in the order they asked. */
    List<Booking> findByStatusOrderByCreatedAtAsc(BookingStatus status, Pageable page);

    /** The booking a rider already created with this Idempotency-Key, if any. Keys are scoped per rider. */
    Optional<Booking> findByRiderIdAndIdempotencyKey(UUID riderId, String idempotencyKey);
}
