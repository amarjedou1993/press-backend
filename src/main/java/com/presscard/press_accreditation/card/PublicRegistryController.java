package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * The public register of accredited journalists.
 *
 * WHY IT EXISTS. An editor verifying a freelancer, a ministry checking an
 * accreditation, a citizen confronted by someone claiming to be press — all
 * need an answer HAPA can stand behind, and none of them holds the card. The
 * verification page answers "is THIS card valid"; this answers "is this PERSON
 * accredited", which is a different question and the more common one.
 *
 * ───────────────────────────────────────────────────────────────────────
 * WHAT IT DISCLOSES, AND WHY THE LIST STOPS WHERE IT DOES
 * ───────────────────────────────────────────────────────────────────────
 *
 * NO PHOTOGRAPH. This is the decision that matters most. The verification page
 * shows one — but only to somebody already holding the card, checking it
 * against the face in front of them. A public list carrying faces is a
 * browsable directory of every journalist in Mauritania, sortable by outlet.
 * For a press regulator that asymmetry is unacceptable: the public gains
 * nothing it needs, and journalists lose something they cannot recover.
 *
 * NO NNI, NO TELEPHONE, NO E-MAIL, NO DATE OF BIRTH. None of them answers
 * "is this person accredited". They answer "how do I find this person", which
 * is not a question a press regulator should help anyone with.
 *
 * NO VERIFICATION TOKEN. It resolves to the photograph. Publishing it would
 * hand back through one endpoint exactly what the other withholds.
 *
 * ONLY CARDS IN FORCE. Suspended, revoked and expired cards are absent —
 * without a trace, not marked as absent. The register answers "who is
 * accredited TODAY"; a public record of who was once suspended is a
 * punishment the règlement does not provide for.
 *
 * ───────────────────────────────────────────────────────────────────────
 *
 * NO PAGINATION PARAMETERS. The whole register is one response: a national
 * press corps is hundreds of people, and search over a complete list in the
 * browser is faster and simpler than paging over the wire. If it ever reaches
 * thousands, this is the place to revisit.
 */
@RestController
@RequestMapping("/api/public/journalists")
public class PublicRegistryController {

    /** One accredited journalist, as the public may see them. */
    public record PublicJournalist(
            String fullName,
            String categoryLabelFr,
            String categoryLabelAr,
            String specialisationFr,
            String specialisationAr,
            String institution,
            String cardNumber,
            LocalDate expiresAt
    ) {}

    public record RegistrySnapshot(
            /** When this was compiled — a register without a date is a rumour. */
            LocalDate compiledAt,
            int total,
            List<PublicJournalist> journalists
    ) {}

    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final PressCategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public PublicRegistryController(CardRepository cardRepository,
                                    ApplicationRepository applicationRepository,
                                    PressCategoryRepository categoryRepository,
                                    UserRepository userRepository) {
        this.cardRepository = cardRepository;
        this.applicationRepository = applicationRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public RegistrySnapshot journalists() {
        List<PublicJournalist> holders = cardRepository.findAllByOrderByIssuedAtDesc()
                .stream()
                // IN FORCE AND UNEXPIRED, both checked here rather than in a
                // query: expiry is derived from a date, and a WHERE clause
                // comparing to CURRENT_DATE would be a second implementation of
                // a rule the entity already owns.
                .filter(card -> card.getStatus() == CardStatus.VALID)
                .filter(card -> !card.isExpired())
                .map(this::toPublic)
                .filter(java.util.Objects::nonNull)
                // Alphabetical: this is a register to be read, not a feed. The
                // issuance order means nothing to anyone consulting it.
                .sorted(Comparator.comparing(
                        PublicJournalist::fullName,
                        java.text.Collator.getInstance(java.util.Locale.FRENCH)))
                .toList();

        return new RegistrySnapshot(LocalDate.now(), holders.size(), holders);
    }

    private PublicJournalist toPublic(Card card) {
        Application application = card.getApplicationId() == null ? null
                : applicationRepository.findById(card.getApplicationId()).orElse(null);
        if (application == null) return null;

        User holder = userRepository.findById(application.getCandidateId()).orElse(null);
        if (holder == null) return null;

        PressCategory category = categoryRepository
                .findById(application.getCategoryId()).orElse(null);

        return new PublicJournalist(
                holder.getFullName(),
                category == null ? null : category.getLabelFr(),
                category == null ? null : category.getLabelAr(),
                // The SNAPSHOTS from the card, not the live application: the
                // register should say what the card in the holder's pocket
                // says, even if they have since changed outlet.
                card.getSpecialisationFr(),
                card.getSpecialisationAr(),
                card.getInstitution(),
                card.getCardNumber(),
                card.getExpiresAt());
    }
}
