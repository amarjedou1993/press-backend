package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.honour.HonourCard;
import com.presscard.press_accreditation.institutional.Institution;
import com.presscard.press_accreditation.institutional.InstitutionRepository;
import com.presscard.press_accreditation.institutional.InstitutionalCard;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turning cards of any series into rows, in a fixed number of queries.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THIS EXISTS BECAUSE THE EXPORT HAD AN N+1 AND THE PV WOULD HAVE HAD A
 * SECOND ONE.
 *
 * CardRegistryExporter looked up the application, the holder, the profile and
 * the category INSIDE its loop — four round trips per card, so a register of
 * six hundred made two thousand four hundred. It was tolerable because the
 * export is occasional.
 *
 * The procès-verbal needs exactly the same facts. Written the same way it
 * would have doubled the problem and put it on a document the Ministry
 * produces every session.
 *
 * ⚠️ FOUR QUERIES, WHATEVER THE SIZE. The maps are built once and read in the
 * loop; no repository call may be added inside it.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class CardRegistryAssembler {

    private final ApplicationRepository applicationRepository;
    private final CandidateProfileRepository profileRepository;
    private final PressCategoryRepository categoryRepository;
    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;

    public CardRegistryAssembler(ApplicationRepository applicationRepository,
                                 CandidateProfileRepository profileRepository,
                                 PressCategoryRepository categoryRepository,
                                 InstitutionRepository institutionRepository,
                                 UserRepository userRepository) {
        this.applicationRepository = applicationRepository;
        this.profileRepository = profileRepository;
        this.categoryRepository = categoryRepository;
        this.institutionRepository = institutionRepository;
        this.userRepository = userRepository;
    }

    /* ══ series A — cards issued on a dossier ══ */

    @Transactional(readOnly = true)
    public List<CardRegistryRow> fromCards(List<Card> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }

        List<Long> applicationIds = cards.stream()
                .map(Card::getApplicationId).filter(Objects::nonNull).distinct().toList();

        Map<Long, Application> applications = applicationRepository
                .findAllById(applicationIds).stream()
                .collect(Collectors.toMap(Application::getId, Function.identity()));

        List<Long> holderIds = applications.values().stream()
                .map(Application::getCandidateId).distinct().toList();

        Map<Long, User> holders = userRepository.findAllById(holderIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Map<Long, CandidateProfile> profiles = profileRepository.findAllById(holderIds).stream()
                .collect(Collectors.toMap(CandidateProfile::getUserId, Function.identity()));

        Map<Long, PressCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));

        return cards.stream().map(card -> {
            Application application = card.getApplicationId() == null ? null
                    : applications.get(card.getApplicationId());
            User holder = application == null ? null
                    : holders.get(application.getCandidateId());
            CandidateProfile profile = holder == null ? null
                    : profiles.get(holder.getId());
            PressCategory category = application == null ? null
                    : categories.get(application.getCategoryId());

            return new CardRegistryRow(
                    card.getCardNumber(),
                    holder == null ? "—" : holder.getFullName(),
                    identityOf(profile),
                    category == null ? "—" : category.getLabelFr(),
                    card.getInstitution(),
                    card.getIssuedAt(),
                    card.getExpiresAt(),
                    statusOf(card.getStatus(), card.isExpired()),
                    holder == null ? null : holder.getPhone(),
                    holder == null ? null : holder.getEmail());
        }).toList();
    }

    /* ══ series B — honour cards ══ */

    /**
     * ⚠️ NO CONTACT DETAILS EXIST FOR THESE, and none are invented.
     *
     * An honour card's holder has no account in this system — the Ministry
     * knows who they are and confers the card in person. The two nulls are
     * the truth, not a gap.
     */
    @Transactional(readOnly = true)
    public List<CardRegistryRow> fromHonourCards(List<HonourCard> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }

        Map<Long, PressCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));

        return cards.stream().map(card -> {
            PressCategory category = card.getCategoryId() == null ? null
                    : categories.get(card.getCategoryId());

            return new CardRegistryRow(
                    card.getCardNumber(),
                    card.getFullName(),
                    card.getIdentityNumber(),
                    category == null ? "—" : category.getLabelFr(),
                    card.getInstitution(),
                    card.getIssuedAt(),
                    card.getExpiresAt(),
                    statusOf(card.getStatus(), card.isExpired()),
                    null, null);
        }).toList();
    }

    /* ══ series C — institutional cards ══ */

    /**
     * ⚠️ THE INSTITUTION COLUMN CARRIES THE BODY, not an outlet typed by hand.
     *
     * A series A card records the outlet a journalist declared. Here it is the
     * institution that filed them — a different kind of fact, and the one a PV
     * of these cards is actually about.
     */
    @Transactional(readOnly = true)
    public List<CardRegistryRow> fromInstitutionalCards(List<InstitutionalCard> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }

        Map<Long, PressCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));

        Map<Long, Institution> institutions = institutionRepository.findAll().stream()
                .collect(Collectors.toMap(Institution::getId, Function.identity()));

        return cards.stream().map(card -> {
            PressCategory category = card.getCategoryId() == null ? null
                    : categories.get(card.getCategoryId());
            Institution institution = institutions.get(card.getInstitutionId());

            return new CardRegistryRow(
                    card.getCardNumber(),
                    card.getFullName(),
                    card.getIdentityNumber(),
                    category == null ? "—" : category.getLabelFr(),
                    institution == null ? "—" : institution.getNameFr(),
                    card.getIssuedAt(),
                    card.getExpiresAt(),
                    statusOf(card.getStatus(), card.isExpired()),
                    null, null);
        }).toList();
    }

    /* ══ internals ══ */

    /**
     * The status as a reader should see it.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ EXPIRY IS DERIVED, NEVER READ FROM THE COLUMN.
     *
     * A card whose date has passed is expired whatever `status` says — that
     * column moves when somebody suspends or revokes, and a nightly job that
     * fails to run leaves it reading VALID for ever.
     *
     * The registry export already did this. Taking status.labelFr() here
     * would have quietly undone it, and a lapsed card would read "Valide" on
     * a document the Ministry signs.
     * ───────────────────────────────────────────────────────────────────
     */
    private static String statusOf(CardStatus status, boolean expired) {
        return expired && status == CardStatus.VALID ? "Expirée" : status.labelFr();
    }

    /**
     * ⚠️ NNI OR PASSPORT, whichever the profile carries.
     *
     * The two are separate columns because they are different documents with
     * different rules — a modulo-97 key on one, none on the other. A register
     * shows the one the holder gave.
     */
    private static String identityOf(CandidateProfile profile) {
        if (profile == null) {
            return "—";
        }
        if (profile.getNni() != null && !profile.getNni().isBlank()) {
            return profile.getNni();
        }
        return profile.getPassportNo() == null || profile.getPassportNo().isBlank()
                ? "—" : profile.getPassportNo();
    }
}