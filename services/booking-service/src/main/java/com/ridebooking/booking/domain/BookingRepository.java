package com.ridebooking.booking.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    /** Open requests, oldest first, so waiting riders are served in the order they asked. */
    List<Booking> findByStatusOrderByCreatedAtAsc(BookingStatus status, Pageable page);
}
