package org.codesentry.app.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PrFindingRepository extends JpaRepository<PrFinding, Long> {
    List<PrFinding> findByReviewId(Long reviewId);
}
