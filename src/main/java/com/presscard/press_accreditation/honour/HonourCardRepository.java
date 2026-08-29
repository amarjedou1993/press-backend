package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.card.CardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HonourCardRepository extends JpaRepository<HonourCard, Long> {

    /** The verification lookup — by token, never by card number. */
    Optional<HonourCard> findByVerificationToken(String verificationToken);

    Optional<HonourCard> findByCardNumber(String cardNumber);

    /** The register, newest first. */
    List<HonourCard> findAllByOrderByIssuedAtDesc();

    /**
     * The ones a producer may make.
     *
     * ⚠️ THE FILTER IS HERE, NOT IN THE RESPONSE MAPPING. A screen is a
     * convenience; this query is the boundary. And expiry is COMPARED rather
     * than read, because isExpired() is derived on every access precisely so
     * that no stored flag can go stale.
     */
    @Query("""
           SELECT h FROM HonourCard h
           WHERE h.status = :status
             AND h.expiresAt >= CURRENT_DATE
           ORDER BY h.cardNumber ASC
           """)
    List<HonourCard> findProducible(@Param("status") CardStatus status);

    /** B - 0001 / 26 — its own sequence, never the A series'. */
    @Query(value = "SELECT nextval('honour_card_number_seq')", nativeQuery = true)
    Long nextCardNumber();
}
