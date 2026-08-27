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

    /**
     * The cards an operator may produce, for one session.
     *
     * ⚠️ THE FILTER IS HERE, NOT IN THE RESPONSE MAPPING.
     *
     * A dropdown is a convenience; this query is the boundary. A printer who
     * bookmarks a card id and returns after a suspension must be refused by
     * the server, not merely fail to see the row.
     *
     * ⚠️ AND EXPIRY IS COMPARED, NOT READ. Card.isExpired() is derived on
     * every access precisely so no stored flag can go stale — which means
     * there is nothing to filter on but the date itself.
     */
    @Query("""
           SELECT c FROM Card c
           WHERE c.status = :status
             AND c.expiresAt >= CURRENT_DATE
             AND c.applicationId IN (
                 SELECT a.id FROM Application a WHERE a.sessionId = :sessionId
             )
           ORDER BY c.cardNumber ASC
           """)
    List<Card> findProducibleBySession(@Param("sessionId") Long sessionId,
                                       @Param("status") CardStatus status);

    /** Sessions that have at least one producible card — the printer's filter. */
    @Query("""
           SELECT DISTINCT a.sessionId FROM Application a
           WHERE a.id IN (
               SELECT c.applicationId FROM Card c
               WHERE c.status = :status AND c.expiresAt >= CURRENT_DATE
           )
           """)
    List<Long> sessionIdsWithProducibleCards(@Param("status") CardStatus status);
}

