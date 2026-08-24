package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * A commission member proposing that a card be withdrawn.
 *
 * REVIEWER-gated. The member proposes; the Authority decides — see
 * CardLifecycleService rule 2 for why the act needs two hands.
 */
@RestController
@RequestMapping("/api/reviewer/revocations")
@PreAuthorize("hasRole('REVIEWER')")
public class ReviewerRevocationController {

    public record ProposeRequest(
            @NotNull(message = "Sélectionnez la carte concernée.") Long cardId,
            @NotNull(message = "Sélectionnez un motif.") Long groundId,
            @NotBlank(message = "Exposez les faits.")
            @Size(max = 4000) String statement
    ) {
    }

    public record GroundOption(
            Long id,
            String code,
            String labelFr,
            String labelAr,
            String hintFr,
            /** True where proposing suspends the card immediately. */
            boolean warrantsImmediateSuspension
    ) {
    }

    private final CardLifecycleService lifecycleService;
    private final RevocationProposalRepository proposalRepository;
    private final RevocationProposalMapper mapper;
    private final UserRepository userRepository;

    public ReviewerRevocationController(
            CardLifecycleService lifecycleService,
            RevocationProposalRepository proposalRepository,
            RevocationProposalMapper mapper,
            UserRepository userRepository) {

        this.lifecycleService = lifecycleService;
        this.proposalRepository = proposalRepository;
        this.mapper = mapper;
        this.userRepository = userRepository;
    }

    /** The grounds HAPA allows, in HAPA's own order. */
    @GetMapping("/grounds")
    public List<GroundOption> grounds() {
        return lifecycleService.activeGrounds().stream()
                .map(ground -> new GroundOption(
                        ground.getId(),
                        ground.getCode(),
                        ground.getLabelFr(),
                        ground.getLabelAr(),
                        ground.getHintFr(),
                        ground.isWarrantsImmediateSuspension()))
                .toList();
    }

    /** Propose a withdrawal. This does not withdraw the card. */
    @PostMapping
    public ProposalResponse propose(
            @Valid @RequestBody ProposeRequest request,
            Principal principal) {

        return mapper.toResponse(lifecycleService.propose(
                request.cardId(),
                currentUserId(principal),
                request.groundId(),
                request.statement()));
    }

    /** Withdraw one's own proposal, before it is decided. */
    @PostMapping("/{id}/withdraw")
    public ProposalResponse withdraw(
            @PathVariable Long id,
            Principal principal) {

        return mapper.toResponse(lifecycleService.withdrawProposal(
                id, currentUserId(principal)));
    }

    /** A member's own proposals: their accountability record. */
    @GetMapping("/mine")
    public List<ProposalResponse> mine(Principal principal) {
        return mapper.toResponses(proposalRepository
                .findByProposedByOrderByProposedAtDesc(
                        currentUserId(principal)));
    }

    private Long currentUserId(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow()
                .getId();
    }
}
