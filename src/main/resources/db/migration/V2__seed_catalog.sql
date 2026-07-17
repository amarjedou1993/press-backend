-- ═══════════════════════════════════════════════════════════════════
-- V2__seed_catalog.sql — press categories + document requirements
-- Source of truth: Requirements V1.3 §A + §D.
-- objection_reasons seed will arrive as V3 once HAPA provides the
-- FR/AR list (committed for week 5).
-- ═══════════════════════════════════════════════════════════════════

INSERT INTO press_categories (code, label_fr, label_ar) VALUES
    ('INTERNATIONAL_MEDIA', 'Média international',
     'وسائل الإعلام الدولية'),
    ('PUBLIC_EMPLOYEE',     'Professionnel des médias, employé public',
     'مهني الإعلام، موظف عمومي'),
    ('FREELANCER',          'Journaliste indépendant',
     'صحفي مستقل');

-- ───────────────────────────────────────────────────────────────────
-- International media: contract AND 3 links   (§D — both mandatory)
-- ───────────────────────────────────────────────────────────────────
INSERT INTO document_requirements (category_id, doc_type, min_count, alternative_group)
SELECT id, 'CONTRACT',  1, NULL FROM press_categories WHERE code = 'INTERNATIONAL_MEDIA';

INSERT INTO document_requirements (category_id, doc_type, min_count, alternative_group)
SELECT id, 'WORK_LINK', 3, NULL FROM press_categories WHERE code = 'INTERNATIONAL_MEDIA';

-- ───────────────────────────────────────────────────────────────────
-- Media professional, public employee: work certificate (§D)
-- ───────────────────────────────────────────────────────────────────
INSERT INTO document_requirements (category_id, doc_type, min_count, alternative_group)
SELECT id, 'WORK_CERTIFICATE', 1, NULL FROM press_categories WHERE code = 'PUBLIC_EMPLOYEE';

-- ───────────────────────────────────────────────────────────────────
-- Freelancer: work certificate OR website OR 3 links (§D)
-- alternative_group = 1 → satisfying ANY ONE of the three suffices
-- ───────────────────────────────────────────────────────────────────
INSERT INTO document_requirements (category_id, doc_type, min_count, alternative_group)
SELECT id, 'WORK_CERTIFICATE', 1, 1 FROM press_categories WHERE code = 'FREELANCER';

INSERT INTO document_requirements (category_id, doc_type, min_count, alternative_group)
SELECT id, 'WEBSITE',          1, 1 FROM press_categories WHERE code = 'FREELANCER';

INSERT INTO document_requirements (category_id, doc_type, min_count, alternative_group)
SELECT id, 'WORK_LINK',        3, 1 FROM press_categories WHERE code = 'FREELANCER';
