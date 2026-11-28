package com.ridebooking.booking.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RatingRepository extends JpaRepository<Rating, UUID> {

    Optional<Rating> findByBookingIdAndRaterId(UUID bookingId, UUID raterId);
}
