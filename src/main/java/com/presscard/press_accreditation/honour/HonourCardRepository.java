//package com.presscard.press_accreditation.honour;
//
//import com.presscard.press_accreditation.card.CardStatus;
//import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.Query;
//import org.springframework.data.repository.query.Param;
//
//import java.util.List;
//import java.util.Optional;
//
//public interface HonourCardRepository extends JpaRepository<HonourCard, Long> {
//
//    /** The verification lookup — by token, never by card number. */
//    Optional<HonourCard> findByVerificationToken(String verificationToken);
//
//    Optional<HonourCard> findByCardNumber(String cardNumber);
//
//    /** The register, newest first. */
//    List<HonourCard> findAllByOrderByIssuedAtDesc();
//
//    /**
//     * The ones a producer may make.
//     *
//     * ⚠️ THE FILTER IS HERE, NOT IN THE RESPONSE MAPPING. A screen is a
//     * convenience; this query is the boundary. And expiry is COMPARED rather
//     * than read, because isExpired() is derived on every access precisely so
//     * that no stored flag can go stale.
//     */
//    @Query("""
//           SELECT h FROM HonourCard h
//           WHERE h.status = :status
//             AND h.expiresAt >= CURRENT_DATE
//           ORDER BY h.cardNumber ASC
//           """)
//    List<HonourCard> findProducible(@Param("status") CardStatus status);
//
//    /** B - 0001 / 26 — its own sequence, never the A series'. */
//    @Query(value = "SELECT nextval('honour_card_number_seq')", nativeQuery = true)
//    Long nextCardNumber();
//}


package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.card.CardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     *
     * ⚠️ AND A PHOTOGRAPH IS REQUIRED — this clause was missing.
     *
     * Without it a card with no face reaches the printer's queue, and
     * HonourArchiveService only logs when BOTH the photo and the QR fail. So
     * a faceless card would leave the building silently, carrying a QR that
     * resolves to a verification page showing no one.
     *
     * A verification without a face verifies nothing: it confirms a number
     * exists, not that the person holding the card is its holder.
     */
    @Query("""
           SELECT h FROM HonourCard h
           WHERE h.status = :status
             AND h.expiresAt >= CURRENT_DATE
             AND h.photoPath IS NOT NULL
           ORDER BY h.cardNumber ASC
           """)
    List<HonourCard> findProducible(@Param("status") CardStatus status);

    /**
     * Whether this identity already holds an honour card.
     *
     * ⚠️ Checked during a bulk import, where the same person appearing twice
     * — in the file, or already in the register — would otherwise consume two
     * card numbers for one holder.
     *
     * Not a database constraint: the Ministry may legitimately re-grant after
     * a revocation, and a UNIQUE column would refuse that. The rule belongs
     * in the import, where it can be reported rather than enforced.
     */
    boolean existsByIdentityNumber(String identityNumber);

    /** Which of these identities already hold a card — one query for a batch. */
    @Query("""
           SELECT h.identityNumber FROM HonourCard h
           WHERE h.identityNumber IN :identityNumbers
           """)
    List<String> findExistingIdentityNumbers(
            @Param("identityNumbers") Collection<String> identityNumbers);

    /** B - 0001 / 26 — its own sequence, never the A series'. */
    @Query(value = "SELECT nextval('honour_card_number_seq')", nativeQuery = true)
    Long nextCardNumber();
}
