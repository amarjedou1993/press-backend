package com.presscard.press_accreditation.category;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The specialisations a candidate may declare — printed on the card as التخصص.
 *
 * ITS OWN CONTROLLER rather than a method on the categories one: they are
 * different vocabularies answering different questions ("under what status do
 * you apply" versus "what do you actually do"), and a candidate picks from
 * both independently.
 *
 * PUBLIC, like the category list: the application form needs it before anyone
 * has logged in, and it is a published list of job titles — nothing here is
 * anyone's personal data.
 */
@RestController
@RequestMapping("/api/public")
public class SpecialisationController {

    public record SpecialisationResponse(
            Long id, String code, String labelFr, String labelAr) {}

    private final SpecialisationRepository specialisationRepository;

    public SpecialisationController(SpecialisationRepository specialisationRepository) {
        this.specialisationRepository = specialisationRepository;
    }

    /** In HAPA's own order — display_order, not alphabetical. */
    @GetMapping("/specialisations")
    public List<SpecialisationResponse> specialisations() {
        return specialisationRepository.findByActiveTrueOrderByDisplayOrderAsc().stream()
                .map(s -> new SpecialisationResponse(
                        s.getId(), s.getCode(), s.getLabelFr(), s.getLabelAr()))
                .toList();
    }
}
