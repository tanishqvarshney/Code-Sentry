package org.codesentry.app.db;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalyzedFileRepository extends CrudRepository<AnalyzedFile, Long> {

    List<AnalyzedFile> findByReviewId(Long reviewId);

    Optional<AnalyzedFile> findByReviewIdAndFilePath(Long reviewId, String filePath);
}
