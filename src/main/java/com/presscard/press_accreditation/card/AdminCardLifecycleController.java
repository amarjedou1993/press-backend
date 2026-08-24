package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Suspension, reinstatement, and the decision on a withdrawal proposal.
 *
 * SUPER_ADMIN only. Suspension is the Authority's alone because it is
 * precautionary and reversible; revocation requires a commission proposal
 * because it is terminal.
 */

@RestController
@RequestMapping("/api/admin/cards")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminCardLifecycleController {

    public record ReasonRequest(
            @NotBlank(message = "Indiquez le motif.")
            @Size(max = 2000) String reason
    ) {
    }

    public record DecisionRequest(
            @Size(max = 2000) String note
    ) {
    }

    /** One entry in a card's status history. */
    public record HistoryEntry(
            String fromStatus,
            String toStatus,
            String toStatusLabelFr,
            String reason,
            String actorName,
            String proposedByName,
            String at
    ) {
    }

    private final CardLifecycleService lifecycleService;
    private final CardRepository cardRepository;
    private final RevocationProposalRepository proposalRepository;
    private final RevocationProposalMapper mapper;
    private final UserRepository userRepository;

    public AdminCardLifecycleController(
            CardLifecycleService lifecycleService,
            CardRepository cardRepository,
            RevocationProposalRepository proposalRepository,
            RevocationProposalMapper mapper,
            UserRepository userRepository) {

        this.lifecycleService = lifecycleService;
        this.cardRepository = cardRepository;
        this.proposalRepository = proposalRepository;
        this.mapper = mapper;
        this.userRepository = userRepository;
    }

    /* -- suspension: immediate, the Authority alone -- */

    @PostMapping("/{cardId}/suspend")
    public void suspend(
            @PathVariable Long cardId,
            @Valid @RequestBody ReasonRequest request,
            Principal principal) {

        lifecycleService.suspend(
                cardId,
                currentUserId(principal),
                request.reason());
    }

    @PostMapping("/{cardId}/reinstate")
    public void reinstate(
            @PathVariable Long cardId,
            @Valid @RequestBody ReasonRequest request,
            Principal principal) {

        lifecycleService.reinstate(
                cardId,
                currentUserId(principal),
                request.reason());
    }

    /* -- revocation: deciding on the commission's proposal -- */

    /** The Authority's queue. */
    @GetMapping("/revocations/pending")
    public List<ProposalResponse> pending() {
        return mapper.toResponses(lifecycleService.pendingProposals());
    }

    @PostMapping("/revocations/{proposalId}/execute")
    public ProposalResponse execute(
            @PathVariable Long proposalId,
            @RequestBody(required = false) DecisionRequest request,
            Principal principal) {

        lifecycleService.executeRevocation(
                proposalId,
                currentUserId(principal),
                request == null ? null : request.note());

        return mapper.toResponse(
                proposalRepository.findById(proposalId).orElseThrow());
    }

    @PostMapping("/revocations/{proposalId}/decline")
    public ProposalResponse decline(
            @PathVariable Long proposalId,
            @Valid @RequestBody ReasonRequest request,
            Principal principal) {

        return mapper.toResponse(lifecycleService.declineRevocation(
                proposalId,
                currentUserId(principal),
                request.reason()));
    }

    /* -- a card's whole life -- */

    @GetMapping("/{cardId}/history")
    public List<HistoryEntry> history(@PathVariable Long cardId) {
        return lifecycleService.historyFor(cardId).stream()
                .map(history -> new HistoryEntry(
                        history.getFromStatus() == null
                                ? null
                                : history.getFromStatus().name(),
                        history.getToStatus().name(),
                        history.getToStatus().labelFr(),
                        history.getReason(),
                        nameOf(history.getActorId()),
                        history.getProposedBy() == null
                                ? null
                                : nameOf(history.getProposedBy()),
                        history.getCreatedAt() == null
                                ? null
                                : history.getCreatedAt().toString()))
                .toList();
    }

    private String nameOf(Long userId) {
        return userId == null
                ? null
                : userRepository.findById(userId)
                .map(User::getFullName)
                .orElse("—");
    }

    private Long currentUserId(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow()
                .getId();
    }
}



