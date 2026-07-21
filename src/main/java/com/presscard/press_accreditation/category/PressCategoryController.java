package com.presscard.press_accreditation.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

interface PressCategoryRepository extends JpaRepository<PressCategory, Long> {}

/**
 * Public list of press-card categories (bilingual labels). Unblocks the
 * sessions page and, in week 3, the submission wizard's category picker.
 */
@RestController
@RequestMapping("/api/public/categories")
class PublicCategoryController {

    public record CategoryResponse(Long id, String code, String labelFr, String labelAr) {}

    private final PressCategoryRepository repository;

    PublicCategoryController(PressCategoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CategoryResponse> list() {
        return repository.findAll().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getCode(), c.getLabelFr(), c.getLabelAr()))
                .toList();
    }
}
