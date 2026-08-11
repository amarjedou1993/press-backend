package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.category.Specialisation;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import org.springframework.stereotype.Component;

/** Two questions the results screen asks about a candidate. */
@Component
public class CandidateProfileLookup {

    private final CandidateProfileRepository profileRepository;
    private final SpecialisationRepository specialisationRepository;

    public CandidateProfileLookup(CandidateProfileRepository profileRepository,
                                  SpecialisationRepository specialisationRepository) {
        this.profileRepository = profileRepository;
        this.specialisationRepository = specialisationRepository;
    }

    /** Whether a card could carry their photograph. */
    public boolean hasPhoto(Long candidateId) {
        return profileRepository.findById(candidateId)
                .map(CandidateProfile::getPhotoPath)
                .isPresent();
    }

    public String specialisationLabelOf(Application application) {
        if (application.getSpecialisationId() == null) return null;
        return specialisationRepository.findById(application.getSpecialisationId())
                .map(Specialisation::getLabelFr)
                .orElse(null);
    }
}


