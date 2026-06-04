package org.codesentry.app.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PrReviewRepository extends JpaRepository<PrReview, Long> {
}
