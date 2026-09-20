package com.presscard.press_accreditation.institutional;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstitutionRepository extends JpaRepository<Institution, Long> {

    Optional<Institution> findByCode(String code);

    List<Institution> findByActiveTrueOrderByNameFrAsc();

    /**
     * ⚠️ Combien de corps peuvent déposer aujourd'hui.
     *
     * Distinct du total : une institution désactivée reste au registre, et
     * les cartes qu'elle a fait octroyer restent valables. Le tableau de bord
     * montre les deux, parce que « trois institutions » et « trois dont une
     * désactivée » ne disent pas la même chose.
     */
    long countByActiveTrue();
}
