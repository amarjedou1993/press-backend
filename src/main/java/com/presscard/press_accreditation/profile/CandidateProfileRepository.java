package com.presscard.press_accreditation.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {

    /** NNI is unique across candidates — used to reject a duplicate identity. */
    Optional<CandidateProfile> findByNni(String nni);

    boolean existsByNni(String nni);

    /** Several profiles at once — the issuable list needs one per candidate. */
    List<CandidateProfile> findByUserIdIn(Collection<Long> userIds);
}
