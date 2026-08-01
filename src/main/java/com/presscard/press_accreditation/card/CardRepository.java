package com.presscard.press_accreditation.card;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, Long> {

    /** The verification lookup — by token, never by card number. */
    Optional<Card> findByVerificationToken(String verificationToken);

    Optional<Card> findByCardNumber(String cardNumber);

    Optional<Card> findByApplicationId(Long applicationId);

    boolean existsByApplicationId(Long applicationId);

    /** The registry, newest first. */
    List<Card> findAllByOrderByIssuedAtDesc();

    /** Cards issued from one session — the batch an admin works with. */
    @Query("""
           SELECT c FROM Card c
           WHERE c.applicationId IN (
               SELECT a.id FROM Application a WHERE a.sessionId = :sessionId
           )
           ORDER BY c.cardNumber ASC
           """)
    List<Card> findBySession(@Param("sessionId") Long sessionId);

    /** The next number in the year's sequence. */
    @Query(value = "SELECT nextval('card_number_seq')", nativeQuery = true)
    Long nextCardNumber();
}

